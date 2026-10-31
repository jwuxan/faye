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
import faye.engine.HandlerMeta;
import faye.engine.HandlerRegistry;
import faye.engine.HostParams;
import faye.engine.Predicates;
import faye.engine.ScreenRegistry;
import faye.engine.TenantPolicy;
import faye.engine.Verifier;
import faye.ir.Call;
import faye.ir.Choose;
import faye.ir.Entry;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The static checks of a flow against a tenant's policy, run before a version is published.
 *
 * <p>Layer L2 of the checks in docs/roadmap/ir-spec.md: L0 (the schema) and L1 ({@link Verifier})
 * have run, and their findings are errors here too. The checker enumerates every path through each
 * entry once, then applies the rules of the entry's kind to those paths. The language has no loops other than
 * bounded {@code retry} and no recursion, so the paths are finite. A predicate is decided when it
 * reads only what is known before the flow runs ({@code tenant.*}, {@code vars.*} written by {@code
 * set}, {@code auth.amr} and {@code auth.acr} from the credentials verified so far on the path, and
 * an empty {@code user.*} until a handler succeeds); otherwise both outcomes are paths. A call either
 * succeeds, proving any nonempty part of its handler's amr, or fails; a call of a handler that binds
 * the subject may also prove someone else, which ends the entry with {@code subject_mismatch}
 * exactly as the engine does, since a subject may be bound before it. A retry's attempt may fail and
 * the next one runs from the state it left. A hook in a step either fails or may set any {@code
 * vars}, which are unknown from then on; the hook of a choose either fails or picks one of the
 * options that may be enabled. The result over-approximates what can happen at run time, so a check
 * that holds on every enumerated path holds on every run.
 */
public final class Checker {

    /** Enumerating more paths than this in one entry is an error rather than a partial check. */
    public static final int MAX_PATHS = 10_000;

    private static final int MAX_EVENTS = 10_000;

    private static final String HOST = "host.";

    /** The events all paths being enumerated may hold between them. */
    private static final long MAX_LIVE_EVENTS = 1_000_000;

    /** Stands for the subject a credential bound; {@code auth.subject} itself is never decided. */
    private static final String SUBJECT = "subject";

    private final HandlerRegistry handlers;
    private final ScreenRegistry screens;
    private final TenantPolicy policy;
    private final Function<String, Optional<HookDef>> hooks;
    private final Map<String, Finding> errors = new LinkedHashMap<>();
    private final Map<String, Finding> warnings = new LinkedHashMap<>();
    /** {@code when} nodes and {@code choose} options some path reached, and those whose body ran. */
    private final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

    private final List<Object> visitOrder = new ArrayList<>();
    private final Set<Object> taken = Collections.newSetFromMap(new IdentityHashMap<>());

    private Checker(
            HandlerRegistry handlers,
            ScreenRegistry screens,
            TenantPolicy policy,
            Function<String, Optional<HookDef>> hooks) {
        this.handlers = handlers;
        this.screens = screens;
        this.policy = policy;
        this.hooks = hooks;
    }

    /** For flows without hooks: a flow with one fails the "hook pinned" check. */
    public static Report check(Flow flow, HandlerRegistry handlers, ScreenRegistry screens, TenantPolicy policy) {
        return check(flow, handlers, screens, policy, hash -> Optional.empty());
    }

    /**
     * @param screens the screens flows may show
     * @param hooks the stored hook definition for a hash, if there is one
     */
    public static Report check(
            Flow flow,
            HandlerRegistry handlers,
            ScreenRegistry screens,
            TenantPolicy policy,
            Function<String, Optional<HookDef>> hooks) {
        return new Checker(handlers, screens, policy, hooks).run(flow);
    }

    private Report run(Flow flow) {
        Verifier.verify(flow, handlers, screens).forEach(p -> error(p.source(), "catalog", p.message()));
        entries(flow);
        Map<String, SourceRef> stepIds = new HashMap<>();
        flow.entries().values().forEach(e -> structure(e.body(), e.kind(), stepIds));

        List<FlowPath> paths = new ArrayList<>();
        flow.entries().forEach((id, entry) -> {
            List<FlowPath> own;
            try {
                own = enumerate(id, entry);
            } catch (TooComplex e) {
                error(
                        entry.body().source(),
                        "too-complex",
                        "entry '" + id + "' has more than " + MAX_PATHS + " paths or a path longer than " + MAX_EVENTS
                                + " steps; split it");
                return;
            }
            paths.addAll(own);
            own.forEach(this::subjectFirst);
            if (entry.kind() == Entry.Kind.AUTHENTICATE) authenticate(id, entry, own);
        });
        reachability();
        return new Report(List.copyOf(errors.values()), List.copyOf(warnings.values()), paths);
    }

    /**
     * The rules of an authenticate entry: every success path resolves the identity before any
     * credential and verifies a credential that binds the subject, reaching {@code faye:2fa} if the
     * tenant requires MFA.
     */
    private void authenticate(String id, Entry entry, List<FlowPath> paths) {
        List<FlowPath> success = paths.stream().filter(FlowPath::succeeded).toList();
        success.forEach(this::credentials);
        if (success.isEmpty() && errors.isEmpty()) {
            warn(entry.body().source(), "entry '" + id + "' never succeeds");
        } else if (success.stream().noneMatch(p -> Acr.TWO_FACTOR.equals(p.acr()))) {
            warn(
                    entry.body().source(),
                    "no path reaches " + Acr.TWO_FACTOR + "; clients that request it in acr_values are refused");
        }
    }

    // ---- checks on the tree ----

    /**
     * A flow has an authenticate entry, and at most one entry of each kind: the engine selects an
     * entry by kind, and how to choose among several is not defined yet.
     */
    private void entries(Flow flow) {
        SourceRef first = flow.entries().values().stream()
                .map(e -> e.body().source())
                .findFirst()
                .orElse(SourceRef.NONE);
        if (flow.entriesOf(Entry.Kind.AUTHENTICATE).isEmpty()) {
            error(first, "entry", "a flow needs an authenticate entry");
        }
        for (Entry.Kind kind : Entry.Kind.values()) {
            List<String> ids = flow.entriesOf(kind);
            for (int i = 1; i < ids.size(); i++) {
                error(
                        Objects.requireNonNull(flow.entries().get(ids.get(i)))
                                .body()
                                .source(),
                        "entry",
                        "entry '" + ids.get(i) + "' is a second " + kind.wireName() + " entry; a flow has at most one");
            }
        }
    }

    private void structure(Node node, Entry.Kind kind, Map<String, SourceRef> stepIds) {
        switch (node) {
            case Seq seq -> seq.children().forEach(c -> structure(c, kind, stepIds));
            case Step step -> {
                SourceRef first = stepIds.putIfAbsent(step.id(), step.source());
                if (first != null) {
                    error(step.source(), "step-id", "step id '" + step.id() + "' is already used at " + first);
                }
                structure(step.body(), kind, stepIds);
            }
            case Screen screen -> {
                // An unknown screen is an L1 finding, reported above
                if (screens.find(screen.type()).isPresent()
                        && policy.screens() != null
                        && !policy.screens().contains(screen.type())) {
                    error(screen.source(), "screen", "screen '" + screen.type() + "' is not enabled for this tenant");
                }
                // Consent shown during the sign in would ask before anyone is signed in, and leave the
                // version without a consent entry, which grants consent with no screen (ADR 0026)
                if (kind == Entry.Kind.AUTHENTICATE && "consent".equals(screen.type())) {
                    error(
                            screen.source(),
                            "consent-screen",
                            "the consent screen belongs on the consent challenge, not in the sign in");
                }
            }
            case Call call -> {
                Optional<Handler> handler = handlers.find(call.handler());
                if (handler.isEmpty()) break;
                if (policy.handlers() != null && !policy.handlers().contains(call.handler())) {
                    error(call.source(), "handler", "handler '" + call.handler() + "' is not enabled for this tenant");
                }
                HandlerMeta meta = handler.get().meta();
                if (kind == Entry.Kind.AUTHENTICATE && "consent.record".equals(call.handler())) {
                    error(
                            call.source(),
                            "consent-call",
                            "consent.record belongs on the consent challenge, not in the sign in");
                }
                if (kind == Entry.Kind.CONSENT && (meta.credential() || meta.bindsSubject())) {
                    error(
                            call.source(),
                            "consent",
                            call.handler() + " verifies a credential; a consent entry only grants access to a subject"
                                    + " its authenticate entry proved");
                }
            }
            case Hook hook -> hook(hook);
            case Choose choose -> {
                if (choose.pick() != null) hook(choose.pick());
                if (choose.options().size() == 1) {
                    warn(choose.source(), "a choose has a single option; use its body directly");
                }
                for (Choose.Opt o : choose.options()) {
                    if (o.guard() != null) hostPaths(o.guard(), o.guardSource());
                    structure(o.body(), kind, stepIds);
                }
            }
            case When when -> {
                hostPaths(when.test(), when.source());
                structure(when.body(), kind, stepIds);
            }
            case Retry retry -> {
                if (!hasScreen(retry.body())) {
                    warn(retry.source(), "retry without a screen asks nothing new on each attempt");
                }
                structure(retry.body(), kind, stepIds);
            }
            case SetVar set -> {}
            case Succeed succeed -> {}
            case Fail fail -> {}
        }
    }

    /**
     * Every {@code host.<x>} a predicate reads is a field of the host contract ({@link
     * HostParams}): a flow cannot come to depend on a key only one host provides (ADR 0025). Held
     * at publish and preview only; a stored version that reads another still loads, and reads null.
     */
    private void hostPaths(Predicate p, SourceRef source) {
        switch (p) {
            case Predicate.And a -> a.all().forEach(q -> hostPaths(q, source));
            case Predicate.Or o -> o.any().forEach(q -> hostPaths(q, source));
            case Predicate.Not n -> hostPaths(n.operand(), source);
            case Predicate.Path a -> hostPath(a.path(), source);
            case Predicate.Exists a -> hostPath(a.path(), source);
            case Predicate.Compare a -> hostPath(a.path(), source);
        }
    }

    private void hostPath(String path, SourceRef source) {
        if (!path.startsWith(HOST)) return;
        if (!HostParams.readable(path.substring(HOST.length()))) {
            error(
                    source,
                    "host",
                    "'" + path + "' is not part of the host contract; a flow reads only "
                            + new java.util.TreeSet<>(HostParams.FIELDS)
                                    .stream().map(f -> HOST + f).toList()
                            + ", and below " + HOST + HostParams.FREE_FORM);
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
     * On a success path of an authenticate entry, a handler that identifies (fills {@code user.*}
     * from what the person typed) succeeds before any credential handler runs, a credential that
     * binds the subject verifies, and the path reaches {@code faye:2fa} if the tenant requires MFA.
     */
    private void credentials(FlowPath path) {
        boolean identified = false;
        boolean verified = false;
        for (Event e : path.events()) {
            if (!(e instanceof Called c)) continue;
            HandlerMeta meta = meta(c.handler());
            if (meta.identifies()) {
                identified |= c.ok();
            } else if (meta.credential() && !identified) {
                error(
                        c.source(),
                        "identity",
                        c.handler() + " runs before any handler identifies the person on the path " + path.describe());
            }
            verified |= c.verifiedSubject();
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

    /**
     * On every path of every entry, a handler that requires a subject runs only after a credential
     * verified one in this flow. It guards credential writes: enrolling a factor or registering a
     * passkey for an account nobody has proven.
     */
    private void subjectFirst(FlowPath path) {
        boolean verified = false;
        for (Event e : path.events()) {
            if (!(e instanceof Called c)) continue;
            if (meta(c.handler()).requiresSubject() && !verified) {
                error(
                        c.source(),
                        "subject",
                        c.handler() + " needs a subject a credential verified earlier in the flow, but runs first"
                                + " on the path " + path.describe());
            }
            verified |= c.verifiedSubject();
        }
    }

    /** A handler the checker did not find has no metadata; it is an L1 error already. */
    private HandlerMeta meta(String handler) {
        return handlers.find(handler).map(Handler::meta).orElse(UNKNOWN);
    }

    private static final HandlerMeta UNKNOWN =
            new HandlerMeta(Set.of(), false, false, false, HandlerMeta.EffectKind.WRITE, HandlerMeta.NO_ARGS);

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

    private List<FlowPath> enumerate(String id, Entry entry) {
        Env empty = new Env(Map.of(), Map.of(), policy.env(), Map.of(), Map.of(), Map.of(), Env.Auth.NONE);
        // An authenticate entry may start with a subject the host remembers (a step up); a consent
        // entry sees whatever its authenticate entry did.
        Set<String> unknown = switch (entry.kind()) {
            case AUTHENTICATE -> Set.of("request", "client", "host", "auth.subject");
            case CONSENT -> Set.of("request", "client", "host", "user", "auth");
        };
        Node body = entry.body();
        List<FlowPath> out = new ArrayList<>();
        for (Run r : walk(body, new Run(List.of(), new Knowledge(empty, unknown), null))) {
            End end = switch (r.stop()) {
                case null -> new Failed("incomplete", body.source());
                case Ended e -> e.end();
                case Failing f -> new Failed(f.reason(), f.source());
            };
            out.add(new FlowPath(id, entry.kind(), r.events(), end));
        }
        return out;
    }

    private List<Run> walk(Node node, Run run) {
        return switch (node) {
            case Seq seq -> {
                List<Run> runs = List.of(run);
                for (Node child : seq.children()) {
                    List<Run> next = new ArrayList<>();
                    for (Run r : runs) {
                        next.addAll(r.stop() == null ? walk(child, r) : List.of(r));
                        // Bounded as it grows: the product of runs and outcomes is built here
                        if (next.size() > MAX_PATHS) throw new TooComplex();
                    }
                    runs = bounded(next);
                }
                yield runs;
            }
            case Step step -> walk(step.body(), run.with(new Entered(step.id(), step.source())));
            case Screen screen -> {
                // Registering a passkey is a credential write, as webauthn.register is: its screen
                // needs the subject a credential verified, or the engine has no one to register
                // for (found by EngineFuzzTest, ADR 0007, O12)
                if (Boolean.TRUE.equals(screen.opts().get("register"))
                        && run.events().stream().noneMatch(e -> e instanceof Called c && c.verifiedSubject())) {
                    error(
                            screen.source(),
                            "subject",
                            "screen '" + screen.type() + "' registers a passkey, which needs a subject a credential"
                                    + " verified earlier in the flow, but shows first on a path");
                }
                yield List.of(run);
            }
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

    /**
     * One path per part of the handler's amr it may prove, one where it fails, and for a handler that
     * binds the subject one where it proves someone else: that ends the entry, unretried.
     */
    private List<Run> call(Call call, Run run) {
        HandlerMeta meta = meta(call.handler());
        boolean binds = meta.bindsSubject();
        List<String> amr = meta.amr().stream().sorted().toList();
        List<Run> out = new ArrayList<>();
        for (List<String> proven : subsets(amr)) {
            out.add(run.with(new Called(call.handler(), proven, binds, false, call.source()))
                    .knowing(run.knowledge().afterOk(call.handler(), proven, binds)));
        }
        out.add(run.with(new Called(call.handler(), null, binds, false, call.source()))
                .stop(new Failing(FlowPath.HANDLER_ERROR, call.source())));
        if (binds && !amr.isEmpty()) {
            out.add(run.with(new Called(call.handler(), amr, binds, true, call.source()))
                    .stop(new Ended(new Failed(FlowPath.SUBJECT_MISMATCH, call.source()))));
        }
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
     * every option has a guard that may be false. With a hook, each option's path has the hook
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
            Predicate guard = option.guard();
            if (guard != null) {
                Tri enabled = run.knowledge().test(guard);
                if (enabled == Tri.FALSE) continue;
                if (enabled == Tri.UNKNOWN) {
                    r = r.with(new Assumed(guard, true, run.knowledge().env(), option.guardSource()));
                    enabledAt = enabledAt.with(
                            new Assumed(guard, true, run.knowledge().env(), option.guardSource()));
                    none = none.with(new Assumed(guard, false, run.knowledge().env(), option.guardSource()));
                } else {
                    mayBeNone = false;
                }
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
            out.addAll(walk(option.body(), r));
            if (out.size() > MAX_PATHS) throw new TooComplex();
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
                    if (r.events().size() > MAX_EVENTS || out.size() + again.size() > MAX_PATHS) {
                        throw new TooComplex();
                    }
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

    /**
     * The runs, if there are not too many and they do not hold too many events between them: the
     * two bounds alone allow MAX_PATHS paths of MAX_EVENTS events each, which a flow of a few
     * nested retries reaches and which no heap holds (found by EngineFuzzTest in CI, ADR 0007).
     */
    private static List<Run> bounded(List<Run> runs) {
        if (runs.size() > MAX_PATHS) throw new TooComplex();
        long events = 0;
        for (Run r : runs) events += r.events().size();
        if (events > MAX_LIVE_EVENTS) throw new TooComplex();
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

    /** A failure unwinding to the innermost retry, or ending the entry if there is none. */
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
                    new Env(env.request(), env.client(), env.tenant(), env.host(), env.user(), vars, env.auth()),
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
                    new Env(env.request(), env.client(), env.tenant(), env.host(), env.user(), env.vars(), auth),
                    Set.copyOf(next));
        }
    }
}
