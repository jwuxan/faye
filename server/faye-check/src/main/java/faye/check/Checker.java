package faye.check;

import faye.check.FlowPath.Assumed;
import faye.check.FlowPath.Called;
import faye.check.FlowPath.Chose;
import faye.check.FlowPath.End;
import faye.check.FlowPath.Entered;
import faye.check.FlowPath.Event;
import faye.check.FlowPath.Failed;
import faye.check.FlowPath.Hooked;
import faye.check.FlowPath.Succeeded;
import faye.engine.Acr;
import faye.engine.Env;
import faye.engine.Handler;
import faye.engine.HandlerRegistry;
import faye.engine.Predicates;
import faye.engine.ScreenType;
import faye.engine.TenantPolicy;
import faye.ir.Call;
import faye.ir.Choose;
import faye.ir.Fail;
import faye.ir.Flow;
import faye.ir.Hook;
import faye.ir.HookDef;
import faye.ir.Node;
import faye.ir.Predicate;
import faye.ir.Retry;
import faye.ir.Screen;
import faye.ir.Seq;
import faye.ir.SetVar;
import faye.ir.SourceRef;
import faye.ir.Step;
import faye.ir.Succeed;
import faye.ir.When;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The static checks of a flow against a tenant's policy, run before a version is published.
 *
 * <p>The checker enumerates every path through each section. The language has no loops other than
 * bounded {@code retry} and no recursion, so the paths are finite. A predicate is decided when it
 * reads only what is known before the flow runs ({@code tenant.*}, {@code vars.*} written by {@code
 * set}, {@code auth.amr} and {@code auth.acr} from the credentials verified so far on the path, and
 * an empty {@code user.*} until a handler succeeds); otherwise both outcomes are paths. A call either
 * succeeds, proving any nonempty part of its handler's amr, or fails. A retry's attempt may fail and
 * the next one runs from the state it left. A hook in a step either fails or may set any {@code
 * vars}, which are unknown from then on; the hook of a choose either fails or picks one of the
 * options that may be enabled. The result over-approximates what can happen at run time, so a check
 * that holds on every enumerated path holds on every run.
 */
public final class Checker {

    /** Enumerating more paths than this in one section is an error rather than a partial check. */
    public static final int MAX_PATHS = 10_000;

    /** The handler that fills {@code user.*} from what the person typed. */
    public static final String IDENTITY_HANDLER = "identity.resolve";

    private static final int MAX_EVENTS = 10_000;

    /** Stands for the subject a credential bound; {@code auth.subject} itself is never decided. */
    private static final String SUBJECT = "subject";

    private final HandlerRegistry handlers;
    private final TenantPolicy policy;
    private final Function<String, Optional<HookDef>> hooks;
    private final Map<String, Finding> errors = new LinkedHashMap<>();
    private final Map<String, Finding> warnings = new LinkedHashMap<>();
    /** {@code when} nodes and {@code choose} options some path reached, and those whose body ran. */
    private final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

    private final List<Object> visitOrder = new ArrayList<>();
    private final Set<Object> taken = Collections.newSetFromMap(new IdentityHashMap<>());

    private Checker(HandlerRegistry handlers, TenantPolicy policy, Function<String, Optional<HookDef>> hooks) {
        this.handlers = handlers;
        this.policy = policy;
        this.hooks = hooks;
    }

    /** For flows without hooks: a flow with one fails the "hook pinned" check. */
    public static Report check(Flow flow, HandlerRegistry handlers, TenantPolicy policy) {
        return check(flow, handlers, policy, hash -> Optional.empty());
    }

    /** @param hooks the stored hook definition for a hash, if there is one */
    public static Report check(
            Flow flow, HandlerRegistry handlers, TenantPolicy policy, Function<String, Optional<HookDef>> hooks) {
        return new Checker(handlers, policy, hooks).run(flow);
    }

    private Report run(Flow flow) {
        Map<String, SourceRef> stepIds = new HashMap<>();
        flow.sections().values().forEach(body -> structure(body, stepIds));

        List<FlowPath> paths = new ArrayList<>();
        flow.sections().forEach((section, body) -> {
            try {
                paths.addAll(enumerate(section, body));
            } catch (TooComplex e) {
                error(
                        body.source(),
                        "too-complex",
                        "the " + section + " section has more than " + MAX_PATHS + " paths or a path longer than "
                                + MAX_EVENTS + " steps; split it");
            }
        });
        reachability();

        List<FlowPath> success = paths.stream()
                .filter(p -> p.section().equals("login") && p.succeeded())
                .toList();
        success.forEach(this::credentials);
        Node login = flow.sections().get("login");
        if (login != null && success.isEmpty() && errors.isEmpty()) {
            warn(login.source(), "login never succeeds");
        } else if (login != null && success.stream().noneMatch(p -> Acr.TWO_FACTOR.equals(p.acr()))) {
            warn(
                    login.source(),
                    "no path reaches " + Acr.TWO_FACTOR + "; clients that request it in acr_values are refused");
        }
        return new Report(List.copyOf(errors.values()), List.copyOf(warnings.values()), paths);
    }

    // ---- checks on the tree ----

    private void structure(Node node, Map<String, SourceRef> stepIds) {
        switch (node) {
            case Seq seq -> seq.children().forEach(c -> structure(c, stepIds));
            case Step step -> {
                SourceRef first = stepIds.putIfAbsent(step.id(), step.source());
                if (first != null) {
                    error(step.source(), "step-id", "step id '" + step.id() + "' is already used at " + first);
                }
                structure(step.body(), stepIds);
            }
            case Screen screen -> {
                if (ScreenType.of(screen.type()).isEmpty()) {
                    error(screen.source(), "screen", "unknown screen type '" + screen.type() + "'");
                } else if (policy.screens() != null && !policy.screens().contains(screen.type())) {
                    error(screen.source(), "screen", "screen '" + screen.type() + "' is not enabled for this tenant");
                }
            }
            case Call call -> {
                if (handlers.find(call.handler()).isEmpty()) {
                    error(call.source(), "handler", "unknown handler '" + call.handler() + "'");
                } else if (policy.handlers() != null && !policy.handlers().contains(call.handler())) {
                    error(call.source(), "handler", "handler '" + call.handler() + "' is not enabled for this tenant");
                }
            }
            case Hook hook -> hook(hook);
            case Choose choose -> {
                if (choose.pick() != null) hook(choose.pick());
                if (choose.options().size() == 1) {
                    warn(choose.source(), "'alternative' has a single option; use its body directly");
                }
                choose.options().forEach(o -> structure(o.body(), stepIds));
            }
            case When when -> structure(when.body(), stepIds);
            case Retry retry -> {
                if (!hasScreen(retry.body())) {
                    warn(retry.source(), "retry without a screen asks nothing new on each attempt");
                }
                structure(retry.body(), stepIds);
            }
            case SetVar set -> {}
            case Succeed succeed -> {}
            case Fail fail -> {}
        }
    }

    /** The hook is stored under its hash, and the tenant grants every egress target it declares. */
    private void hook(Hook hook) {
        Optional<HookDef> def = hooks.apply(hook.hash());
        if (def.isEmpty()) {
            error(hook.source(), "hook", "hook '" + hook.id() + "' (" + hook.hash() + ") is not stored");
            return;
        }
        for (String target : def.get().http()) {
            if (!policy.egress().contains(target)) {
                error(
                        hook.source(),
                        "hook",
                        "hook '" + hook.id() + "' calls egress target '" + target
                                + "', which is not granted to this tenant");
            }
        }
    }

    private static boolean hasScreen(Node node) {
        return switch (node) {
            case Screen s -> true;
            case Seq s -> s.children().stream().anyMatch(Checker::hasScreen);
            case Step s -> hasScreen(s.body());
            case When w -> hasScreen(w.body());
            case Retry r -> hasScreen(r.body());
            case Choose c -> c.options().stream().anyMatch(o -> hasScreen(o.body()));
            case Call c -> false;
            case Hook h -> false;
            case SetVar s -> false;
            case Succeed s -> false;
            case Fail f -> false;
        };
    }

    // ---- checks on the paths ----

    /**
     * Every login path to success resolves the identity before any credential handler runs, verifies
     * a credential that binds the subject, and reaches {@code faye:2fa} if the tenant requires MFA.
     */
    private void credentials(FlowPath path) {
        boolean identified = false;
        boolean verified = false;
        for (Event e : path.events()) {
            if (!(e instanceof Called c)) continue;
            if (c.handler().equals(IDENTITY_HANDLER)) {
                identified |= c.ok();
            } else if (credential(c.handler()) && !identified) {
                error(
                        c.source(),
                        "identity",
                        c.handler() + " runs before " + IDENTITY_HANDLER + " on the path " + path.describe());
            }
            verified |= c.amr() != null && c.bindsSubject() && !c.amr().isEmpty();
        }
        if (!verified) {
            error(path.decidedAt(), "credential", "a path succeeds without verifying a credential: " + path.describe());
        } else if (policy.mfaRequired() && !Acr.TWO_FACTOR.equals(path.acr())) {
            error(
                    path.decidedAt(),
                    "mfa",
                    "the tenant requires MFA, but a path succeeds with " + path.acr() + ": " + path.describe());
        }
    }

    private boolean credential(String handler) {
        return handlers.find(handler).map(h -> !h.amr().isEmpty()).orElse(false);
    }

    private void reachability() {
        for (Object node : visitOrder) {
            if (taken.contains(node)) continue;
            switch (node) {
                case When w -> warn(w.source(), "never runs: its condition is false on every path that reaches it");
                case Choose.Opt o ->
                    warn(o.source(), "option '" + o.id() + "' is never offered: its condition is false on every path");
                default -> throw new IllegalStateException(node.toString());
            }
        }
    }

    // ---- path enumeration ----

    private List<FlowPath> enumerate(String section, Node body) {
        Env empty = new Env(Map.of(), Map.of(), policy.env(), Map.of(), Map.of(), Map.of(), Env.Auth.NONE);
        // A login may start with a subject Hydra remembers; a consent sees whatever its login did.
        Set<String> unknown = "login".equals(section)
                ? Set.of("request", "client", "hydra", "auth.subject")
                : Set.of("request", "client", "hydra", "user", "auth");
        List<FlowPath> out = new ArrayList<>();
        for (Run r : walk(body, new Run(List.of(), new Knowledge(empty, unknown), null))) {
            End end = switch (r.stop()) {
                case null -> new Failed("incomplete", body.source());
                case Ended e -> e.end();
                case Failing f -> new Failed(f.reason(), f.source());
            };
            out.add(new FlowPath(section, r.events(), end));
        }
        return out;
    }

    private List<Run> walk(Node node, Run run) {
        return switch (node) {
            case Seq seq -> {
                List<Run> runs = List.of(run);
                for (Node child : seq.children()) {
                    List<Run> next = new ArrayList<>();
                    for (Run r : runs) next.addAll(r.stop() == null ? walk(child, r) : List.of(r));
                    runs = bounded(next);
                }
                yield runs;
            }
            case Step step -> walk(step.body(), run.with(new Entered(step.id(), step.source())));
            case Screen screen -> List.of(run);
            case Call call -> call(call, run);
            case Hook hook ->
                bounded(List.of(
                        run.with(new Hooked(hook.id(), true, null, hook.source()))
                                .knowing(run.knowledge().afterHook()),
                        run.with(new Hooked(hook.id(), false, null, hook.source()))
                                .stop(new Failing(FlowPath.HOOK_ERROR, hook.source()))));
            case Choose choose -> choose(choose, run);
            case When when -> {
                visit(when);
                yield switch (run.knowledge().test(when.test())) {
                    case TRUE -> {
                        taken.add(when);
                        yield walk(when.body(), run);
                    }
                    case FALSE -> List.of(run);
                    case UNKNOWN -> {
                        taken.add(when);
                        List<Run> out = new ArrayList<>(walk(
                                when.body(),
                                run.with(new Assumed(
                                        when.test(), true, run.knowledge().env(), when.source()))));
                        out.add(run.with(
                                new Assumed(when.test(), false, run.knowledge().env(), when.source())));
                        yield bounded(out);
                    }
                };
            }
            case Retry retry -> retry(retry, run);
            case SetVar set -> List.of(run.knowing(run.knowledge().withVar(set.name(), set.value())));
            case Succeed succeed -> List.of(run.stop(new Ended(new Succeeded(succeed.source()))));
            case Fail fail -> List.of(run.stop(new Ended(new Failed(fail.reason(), fail.source()))));
        };
    }

    /** One path per part of the handler's amr it may prove, and one where it fails. */
    private List<Run> call(Call call, Run run) {
        Optional<Handler> handler = handlers.find(call.handler());
        boolean binds = handler.map(Handler::bindsSubject).orElse(false);
        List<String> amr = handler.map(h -> h.amr().stream().sorted().toList()).orElse(List.of());
        List<Run> out = new ArrayList<>();
        for (List<String> proven : subsets(amr)) {
            out.add(run.with(new Called(call.handler(), proven, binds, call.source()))
                    .knowing(run.knowledge().afterOk(call.handler(), proven, binds)));
        }
        out.add(run.with(new Called(call.handler(), null, binds, call.source()))
                .stop(new Failing(FlowPath.HANDLER_ERROR, call.source())));
        return bounded(out);
    }

    /** Nonempty subsets of a sorted list, largest first; {@code [[]]} for an empty list. */
    private static List<List<String>> subsets(List<String> amr) {
        if (amr.isEmpty()) return List.of(List.of());
        List<List<String>> out = new ArrayList<>();
        for (int mask = (1 << amr.size()) - 1; mask > 0; mask--) {
            List<String> subset = new ArrayList<>();
            for (int i = 0; i < amr.size(); i++) {
                if ((mask & (1 << i)) != 0) subset.add(amr.get(i));
            }
            out.add(subset);
        }
        return out;
    }

    /**
     * Each option that may be enabled is a path; so is "no option enabled" ({@code no_method}) when
     * every option has a condition that may be false. With a hook, each option's path has the hook
     * pick it, and one more path has the hook fail once the first option that may be enabled is.
     */
    private List<Run> choose(Choose choose, Run run) {
        List<Run> out = new ArrayList<>();
        Run none = run;
        @Nullable Run hookFails = null;
        boolean mayBeNone = true;
        for (Choose.Opt option : choose.options()) {
            visit(option);
            Run r = run.with(new Chose(option.id(), option.source()));
            // The run up to the hook: the option is enabled, none chosen yet
            Run enabledAt = run;
            Node body = option.body();
            if (body instanceof When w) {
                Tri enabled = run.knowledge().test(w.test());
                if (enabled == Tri.FALSE) continue;
                if (enabled == Tri.UNKNOWN) {
                    r = r.with(new Assumed(w.test(), true, run.knowledge().env(), w.source()));
                    enabledAt = enabledAt.with(
                            new Assumed(w.test(), true, run.knowledge().env(), w.source()));
                    none = none.with(
                            new Assumed(w.test(), false, run.knowledge().env(), w.source()));
                } else {
                    mayBeNone = false;
                }
                body = w.body();
            } else {
                mayBeNone = false;
            }
            Hook pick = choose.pick();
            if (pick != null) {
                if (hookFails == null) {
                    hookFails = enabledAt
                            .with(new Hooked(pick.id(), false, null, pick.source()))
                            .stop(new Failing(FlowPath.HOOK_ERROR, pick.source()));
                }
                r = r.with(new Hooked(pick.id(), true, option.id(), pick.source()));
            }
            taken.add(option);
            out.addAll(walk(body, r));
        }
        if (hookFails != null) out.add(hookFails);
        if (mayBeNone) out.add(none.stop(new Failing("no_method", choose.source())));
        return bounded(out);
    }

    /**
     * Each attempt runs from the state the failed attempt before it left ({@code vars}, {@code user}
     * and {@code auth} carry over in the engine), so a path may fail any number of attempts before
     * one succeeds. A failure on the last attempt unwinds further.
     */
    private List<Run> retry(Retry retry, Run run) {
        List<Run> out = new ArrayList<>();
        List<Run> starts = List.of(run);
        for (int attempt = 1; attempt <= retry.attempts() && !starts.isEmpty(); attempt++) {
            List<Run> again = new ArrayList<>();
            for (Run start : starts) {
                for (Run r : walk(retry.body(), start)) {
                    if (r.events().size() > MAX_EVENTS) throw new TooComplex();
                    if (r.stop() instanceof Failing && attempt < retry.attempts()) {
                        again.add(new Run(r.events(), r.knowledge(), null));
                    } else {
                        out.add(r);
                    }
                }
            }
            starts = bounded(again);
        }
        return bounded(out);
    }

    private void visit(Object node) {
        if (visited.add(node)) visitOrder.add(node);
    }

    private static List<Run> bounded(List<Run> runs) {
        if (runs.size() > MAX_PATHS) throw new TooComplex();
        return runs;
    }

    private void error(SourceRef at, String kind, String message) {
        errors.putIfAbsent(kind + "@" + at, new Finding(at, message));
    }

    private void warn(SourceRef at, String message) {
        warnings.putIfAbsent(message + "@" + at, new Finding(at, message));
    }

    // ---- enumeration state ----

    private static final class TooComplex extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TooComplex() {
            super(null, null, false, false);
        }
    }

    private sealed interface Stop permits Ended, Failing {}

    private record Ended(End end) implements Stop {}

    /** A failure unwinding to the innermost retry, or ending the section if there is none. */
    private record Failing(String reason, SourceRef source) implements Stop {}

    /** A partial path: its events so far, what is known at its end, and why it stopped, if it did. */
    private record Run(
            List<Event> events,
            Knowledge knowledge,
            @Nullable Stop stop) {

        Run with(Event event) {
            List<Event> next = new ArrayList<>(events);
            next.add(event);
            return new Run(next, knowledge, stop);
        }

        Run knowing(Knowledge next) {
            return new Run(events, next, stop);
        }

        Run stop(Stop how) {
            return new Run(events, knowledge, how);
        }
    }

    private enum Tri {
        TRUE,
        FALSE,
        UNKNOWN
    }

    /**
     * The environment as far as the checker knows it: values in {@code env}, except at the paths in
     * {@code unknown} (a namespace such as {@code user}, or a path such as {@code auth.subject}).
     */
    private record Knowledge(Env env, Set<String> unknown) {

        Tri test(Predicate p) {
            return switch (p) {
                case Predicate.And a -> {
                    Tri out = Tri.TRUE;
                    for (Predicate q : a.all()) {
                        Tri t = test(q);
                        if (t == Tri.FALSE) yield Tri.FALSE;
                        if (t == Tri.UNKNOWN) out = Tri.UNKNOWN;
                    }
                    yield out;
                }
                case Predicate.Or o -> {
                    Tri out = Tri.FALSE;
                    for (Predicate q : o.any()) {
                        Tri t = test(q);
                        if (t == Tri.TRUE) yield Tri.TRUE;
                        if (t == Tri.UNKNOWN) out = Tri.UNKNOWN;
                    }
                    yield out;
                }
                case Predicate.Not n -> {
                    Tri t = test(n.operand());
                    yield t == Tri.UNKNOWN ? t : t == Tri.TRUE ? Tri.FALSE : Tri.TRUE;
                }
                case Predicate.Path a -> atom(a.path(), p);
                case Predicate.Exists a -> atom(a.path(), p);
                case Predicate.Compare a -> atom(a.path(), p);
            };
        }

        private Tri atom(String path, Predicate p) {
            for (String u : unknown) {
                if (path.equals(u) || path.startsWith(u + ".")) return Tri.UNKNOWN;
            }
            return Predicates.test(p, env) ? Tri.TRUE : Tri.FALSE;
        }

        Knowledge withVar(String name, Object value) {
            Map<String, Object> vars = new HashMap<>(env.vars());
            vars.put(name, value);
            return new Knowledge(
                    new Env(env.request(), env.client(), env.tenant(), env.hydra(), env.user(), vars, env.auth()),
                    unknown);
        }

        /** A hook in a step may set any {@code vars}. */
        Knowledge afterHook() {
            Set<String> next = new HashSet<>(unknown);
            next.add("vars");
            return new Knowledge(env, Set.copyOf(next));
        }

        /**
         * Any handler that succeeds may change {@code user.*}; a credential adds to {@code auth}. The
         * subject it binds stays unknown, so {@link #SUBJECT} only marks that there is one.
         */
        Knowledge afterOk(String handler, List<String> proven, boolean binds) {
            Set<String> next = new HashSet<>(unknown);
            next.add("user");
            Env.Auth auth = env.auth();
            if (!proven.isEmpty()) {
                List<Env.Verified> verified = new ArrayList<>(auth.verified());
                verified.add(new Env.Verified(handler, proven));
                auth = new Env.Auth(binds ? SUBJECT : auth.subject(), verified);
            }
            return new Knowledge(
                    new Env(env.request(), env.client(), env.tenant(), env.hydra(), env.user(), env.vars(), auth),
                    Set.copyOf(next));
        }
    }
}
