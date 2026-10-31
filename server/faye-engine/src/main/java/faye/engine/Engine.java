package faye.engine;

import faye.engine.FlowException.Code;
import faye.ir.Call;
import faye.ir.Choose;
import faye.ir.Entry;
import faye.ir.Fail;
import faye.ir.Hook;
import faye.ir.Node;
import faye.ir.Predicate;
import faye.ir.Retry;
import faye.ir.Screen;
import faye.ir.Seq;
import faye.ir.SetVar;
import faye.ir.Step;
import faye.ir.Succeed;
import faye.ir.When;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Small step interpreter over an explicit continuation stack; docs/roadmap/ir-spec.md is the
 * normative semantics it implements. A flow runs until it reaches a screen, at which point its
 * {@link FlowState} is persisted and the screen returned; a submission reloads the state and
 * continues. Nothing about a flow lives in this object between requests.
 *
 * <p>Every flow is bound to the browser that started it: {@link #start} takes a binder, a secret
 * the caller hands the browser in a cookie, and {@link #view} and {@link #submit} refuse a flow
 * whose binder does not match exactly as they refuse one that does not exist.
 */
public final class Engine {

    private static final System.Logger LOG = System.getLogger(Engine.class.getName());

    public static final String DEFAULT_TENANT = "default";

    /** Why a host request is rejected when its version cannot run here (ir-spec.md, Checks). */
    public static final String FLOW_UNAVAILABLE = "flow_unavailable";

    /** Why an entry fails when applying one of its writes threw. */
    public static final String WRITE_ERROR = "write_error";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final FlowVersions versions;
    private final HandlerRegistry handlers;
    private final FlowStore store;
    private final FlowHost host;
    private final TenantPolicies tenants;
    private final HookRunner hooks;
    private final Previews previews;
    private final ScreenRegistry screens;

    public Engine(FlowVersions versions, HandlerRegistry handlers, FlowStore store, FlowHost host) {
        this(versions, handlers, store, host, Map.of());
    }

    public Engine(
            FlowVersions versions,
            HandlerRegistry handlers,
            FlowStore store,
            FlowHost host,
            Map<String, TenantPolicy> tenants) {
        this(versions, handlers, store, host, tenants, HookRunner.NONE);
    }

    /**
     * @param tenants policy per tenant id; a tenant not listed gets {@link TenantPolicy#DEFAULT}
     * @param hooks runs the hooks flows pin
     */
    public Engine(
            FlowVersions versions,
            HandlerRegistry handlers,
            FlowStore store,
            FlowHost host,
            Map<String, TenantPolicy> tenants,
            HookRunner hooks) {
        this(versions, handlers, store, host, TenantPolicies.of(tenants), hooks);
    }

    /**
     * @param tenants looked up when a flow starts
     * @param hooks runs the hooks flows pin
     */
    public Engine(
            FlowVersions versions,
            HandlerRegistry handlers,
            FlowStore store,
            FlowHost host,
            TenantPolicies tenants,
            HookRunner hooks) {
        this(versions, handlers, store, host, tenants, hooks, Previews.NONE);
    }

    /**
     * @param tenants looked up when a flow starts
     * @param hooks runs the hooks flows pin
     * @param previews what preview clients run, and where their traces go
     */
    public Engine(
            FlowVersions versions,
            HandlerRegistry handlers,
            FlowStore store,
            FlowHost host,
            TenantPolicies tenants,
            HookRunner hooks,
            Previews previews) {
        this(versions, handlers, store, host, tenants, hooks, previews, ScreenRegistry.builtIn());
    }

    /** @param screens the screens flows may show */
    public Engine(
            FlowVersions versions,
            HandlerRegistry handlers,
            FlowStore store,
            FlowHost host,
            TenantPolicies tenants,
            HookRunner hooks,
            Previews previews,
            ScreenRegistry screens) {
        this.versions = versions;
        this.handlers = handlers;
        this.store = store;
        this.host = host;
        this.tenants = tenants;
        this.hooks = hooks;
        this.previews = previews;
        this.screens = screens;
    }

    /**
     * Begins a flow for a challenge. A login runs the tenant's current version, or for a preview
     * client the version {@link Previews} gives. A consent runs the version its login ran, found
     * through the {@code flowId} the login put in the host's context, and sees that login's {@code
     * user} and {@code auth}.
     *
     * @param binder the secret the caller gives the browser; every later view and submit must
     *     present it
     */
    public EngineResponse start(HostKind kind, String challenge, Map<String, Object> request, String binder) {
        HostRequest req = host.load(kind, challenge);
        @Nullable String subject = req.params().get("subject") instanceof String s && !s.isEmpty() ? s : null;
        boolean skip = Boolean.TRUE.equals(req.params().get("skip"));
        boolean preview = Previews.isPreview(req.client());
        String tenant = tenantOf(req);
        FlowVersion version;
        Map<String, Object> user = Map.of();
        Map<String, Object> settings = tenants.get(tenant).env();
        Env.Auth auth = Env.Auth.NONE;

        switch (kind) {
            case LOGIN -> {
                Optional<FlowVersion> found = preview ? previews.version(tenant) : Optional.of(current(tenant));
                if (found.isEmpty()) return host.reject(req, "access_denied", "preview_unavailable");
                version = found.get();
                if (skip && subject != null) {
                    // The host remembers who, not how: a remembered session counts as one factor.
                    if (Acr.satisfies(Acr.ONE_FACTOR, req.params().get("acr_values"))
                            && Acr.satisfies(Acr.ONE_FACTOR, required(settings))) {
                        return host.accept(req, new Authenticated(subject, List.of(), Acr.ONE_FACTOR, Map.of()));
                    }
                    // Step up: run the flow, but only for the subject the host already knows.
                    auth = new Env.Auth(subject, List.of());
                }
            }
            case CONSENT -> {
                if (subject == null) throw new IllegalStateException("consent request without a subject");
                Optional<FlowState> login = linkedLogin(req, subject);
                if (login.isPresent()) {
                    tenant = login.get().tenantId();
                    // The login's version runs its consent; if it can no longer be read, nothing else does
                    Optional<FlowVersion> ran = versions.get(login.get().versionHash());
                    if (ran.isEmpty()) return host.reject(req, "access_denied", FLOW_UNAVAILABLE);
                    version = ran.get();
                    user = login.get().env().user();
                    settings = login.get().env().tenant();
                    auth = login.get().env().auth();
                } else {
                    Optional<FlowVersion> found = preview ? previews.version(tenant) : Optional.of(current(tenant));
                    if (found.isEmpty()) return host.reject(req, "access_denied", "preview_unavailable");
                    version = found.get();
                    auth = new Env.Auth(subject, List.of());
                }
                // Granted without a screen: remembered, or a version with no consent entry at all
                if (skip || version.flow().entriesOf(Entry.Kind.CONSENT).isEmpty()) {
                    return host.accept(req, consented(req, user, subject));
                }
            }
            case LOGOUT -> throw new IllegalArgumentException("logout runs no flow entry");
            default -> throw new IllegalArgumentException(kind.toString());
        }
        // A version that names a handler or screen this engine lacks, or has no entry for the
        // challenge, never runs here: the checker refuses both at publish, so this only happens when
        // the catalogs changed since.
        Optional<String> entry = EntrySelector.select(version.flow(), kind);
        if (entry.isEmpty() || !runnable(version)) return host.reject(req, "access_denied", FLOW_UNAVAILABLE);
        if (preview) {
            Map<String, Object> overridden = new LinkedHashMap<>(request);
            overridden.putAll(previews.request(tenant));
            request = overridden;
        }
        Env env = new Env(request, req.client(), settings, req.params(), user, Map.of(), auth);
        FlowState initial = new FlowState(
                randomId("f_"),
                tenant,
                version.hash(),
                kind,
                entry.get(),
                challenge,
                hash(binder),
                List.of(new Frame.Eval(List.of())),
                env,
                null,
                List.of(),
                0);

        List<TraceEvent> trace = new ArrayList<>();
        trace.add(TraceEvent.of("start", version.hash(), entry.get()));
        Run run = run(initial, version, Submission.NONE, 0, trace);
        // Only a flow that waits is stored; one that ended at once has nothing to resume
        if (run instanceof Suspended) store.create(run.state());
        run = commit(run, trace);
        emit(run.state(), trace);
        return switch (run) {
            case Suspended s -> view(s.state());
            case Ended e -> finish(req, e);
        };
    }

    /** The screen a flow is currently waiting on. */
    public ScreenView view(String flowId, String binder) {
        return view(load(flowId, binder));
    }

    /**
     * Answers the current screen. A stale nonce (already answered, or not this screen) and a lost
     * race against a concurrent submit are both refused without changing the stored state or any
     * account: staged writes are applied only by the submit whose saved state ended the entry
     * successfully.
     */
    public EngineResponse submit(String flowId, String binder, String nonce, Submission input) {
        FlowState state = load(flowId, binder);
        FlowState.Awaiting awaiting = Objects.requireNonNull(state.awaiting(), "load refuses finished flows");
        if (!awaiting.nonce().equals(nonce)) {
            throw new FlowException(Code.STALE_NONCE);
        }
        if (!screen(awaiting.screen()).accepts(input, awaiting.data())) {
            throw new FlowException(Code.INVALID_SUBMISSION);
        }
        FlowVersion version = pinned(state);

        List<TraceEvent> trace = new ArrayList<>();
        Run run = run(state, version, input.withContext(awaiting.data()), state.revision() + 1, trace);
        // Save before any lasting change and before talking to the host: only the submit that wins
        // the revision race may apply staged writes or accept.
        if (!store.replace(run.state())) return lost(state);
        run = commit(run, trace);
        emit(run.state(), trace);
        return switch (run) {
            case Suspended s -> view(s.state());
            case Ended e -> finish(host.load(state.kind(), state.hostRef()), e);
        };
    }

    /**
     * The answer to a submit whose run lost the revision race to another answer of the same screen
     * from the same browser (it holds the binder): the screen that answer led to, as if this request
     * had been the winner; {@code flow_finished} if it finished the flow, whose redirect only the
     * winner carries. Nothing the losing run did lasts.
     */
    private EngineResponse lost(FlowState answered) {
        FlowState now = store.load(answered.flowId()).orElseThrow(() -> new FlowException(Code.FLOW_NOT_FOUND));
        if (now.revision() == answered.revision()) throw new FlowException(Code.CONFLICT);
        if (now.awaiting() == null) throw new FlowException(Code.FLOW_FINISHED);
        return view(now);
    }

    // ---- interpreter ----

    private sealed interface Run permits Suspended, Ended {
        FlowState state();
    }

    private record Suspended(FlowState state) implements Run {}

    /**
     * @param failure null when the entry succeeded
     * @param writes the writes the entry staged, to apply because it succeeded; empty on a failure
     */
    private record Ended(FlowState state, @Nullable String failure, List<Effect> writes) implements Run {}

    /** @param trace collects what the run does, for preview flows */
    private Run run(FlowState state, FlowVersion version, Submission input, long revision, List<TraceEvent> trace) {
        Entry entry = Objects.requireNonNull(version.flow().entries().get(state.entry()), "start selected the entry");
        Node root = entry.body();
        List<Frame> stack = new ArrayList<>(state.stack());
        Env env = state.env();
        // Lasting changes handlers ask for, saved with the state this run produces
        List<Effect> effects = new ArrayList<>(state.pending());
        FlowState.@Nullable Awaiting answered = state.awaiting();
        // An answer this submission carries for a later screen (passkey autofill on identifier).
        @Nullable
        String hosted = answered == null
                ? null
                : screen(answered.screen()).hosted(input).orElse(null);
        // The failure a retry is rerunning after, shown on the next screen.
        @Nullable String error = null;
        @Nullable Integer attemptsLeft = null;

        while (!stack.isEmpty()) {
            Frame frame = stack.removeLast();
            List<Integer> path = frame.path();
            @Nullable String failed = null;
            switch (frame) {
                case Frame.StepExit exit -> {}
                case Frame.RetryExit exit -> {}
                case Frame.SeqNext next -> {
                    Seq seq = (Seq) resolve(root, path);
                    if (next.next() < seq.children().size()) {
                        stack.add(new Frame.SeqNext(path, next.next() + 1));
                        stack.add(new Frame.Eval(child(path, next.next())));
                    }
                }
                case Frame.Pick pick -> {
                    List<Choose.Opt> options = ((Choose) resolve(root, path)).options();
                    String method = input.string("method");
                    for (int i = 0; i < options.size(); i++) {
                        if (options.get(i).id().equals(method)) {
                            trace.add(TraceEvent.of("option", method, "user"));
                            stack.add(new Frame.Eval(child(path, i)));
                        }
                    }
                }
                case Frame.Eval eval -> {
                    switch (resolve(root, path)) {
                        case Seq seq -> stack.add(new Frame.SeqNext(path, 0));
                        case Step step -> {
                            trace.add(TraceEvent.of("step", step.id(), null));
                            stack.add(new Frame.StepExit(path, step.id()));
                            stack.add(new Frame.Eval(child(path, 0)));
                        }
                        case When when -> {
                            if (Predicates.test(when.test(), env)) stack.add(new Frame.Eval(child(path, 0)));
                        }
                        case Retry retry -> {
                            stack.add(new Frame.RetryExit(path, 1));
                            stack.add(new Frame.Eval(child(path, 0)));
                        }
                        case SetVar set -> env = env.withVar(set.name(), set.value());
                        case Choose choose -> {
                            List<Integer> enabled = new ArrayList<>();
                            for (int i = 0; i < choose.options().size(); i++) {
                                Predicate guard = choose.options().get(i).guard();
                                if (guard == null || Predicates.test(guard, env)) enabled.add(i);
                            }
                            @Nullable Integer chosen = enabled.size() == 1 ? enabled.get(0) : null;
                            @Nullable Integer picked = null;
                            if (choose.pick() != null && !enabled.isEmpty()) {
                                List<String> ids = enabled.stream()
                                        .map(i -> choose.options().get(i).id())
                                        .toList();
                                switch (runHook(state, choose.pick(), env, ids)) {
                                    case HookOutput.None none -> {}
                                    // The hook of a choose only picks; vars are set by hooks in steps
                                    case HookOutput.Set set -> failed = HookOutput.HOOK_ERROR;
                                    case HookOutput.Fail fail -> failed = fail.reason();
                                    case HookOutput.Pick pick -> {
                                        int at = ids.indexOf(pick.option());
                                        if (at < 0) {
                                            failed = HookOutput.HOOK_ERROR;
                                        } else {
                                            picked = enabled.get(at);
                                        }
                                    }
                                }
                                trace.add(TraceEvent.of("hook", choose.pick().id(), failed == null ? "ok" : failed));
                            }
                            if (failed != null) break;
                            String by = "only";
                            for (int i : enabled) {
                                Optional<Screen> first =
                                        firstScreen(choose.options().get(i).body());
                                if (hosted != null
                                        && first.isPresent()
                                        && screen(first.get().type())
                                                .answeredBy(hosted, first.get().opts())) {
                                    chosen = i;
                                    by = "user";
                                }
                            }
                            // A hook's pick wins over the user's earlier answer
                            if (picked != null) {
                                chosen = picked;
                                by = "hook";
                            }
                            if (chosen != null) {
                                trace.add(TraceEvent.of(
                                        "option", choose.options().get(chosen).id(), by));
                                stack.add(new Frame.Eval(child(path, chosen)));
                            } else if (enabled.isEmpty()) {
                                failed = "no_method";
                            } else {
                                stack.add(new Frame.Pick(path));
                                Map<String, Object> data = Map.of(
                                        "options",
                                        enabled.stream()
                                                .map(i ->
                                                        choose.options().get(i).id())
                                                .toList());
                                trace.add(TraceEvent.of("screen", ScreenRegistry.METHOD_PICKER, null));
                                return suspend(
                                        state,
                                        stack,
                                        env,
                                        effects,
                                        revision,
                                        path,
                                        ScreenRegistry.METHOD_PICKER,
                                        data,
                                        error,
                                        attemptsLeft);
                            }
                        }
                        case Screen node -> {
                            ScreenSpec type = screen(node.type());
                            if (hosted != null && type.answeredBy(hosted, node.opts())) {
                                hosted = null;
                                break;
                            }
                            Map<String, Object> previous = answered != null
                                            && answered.path().equals(path)
                                            && answered.screen().equals(type.type())
                                    ? answered.data()
                                    : Map.of();
                            trace.add(TraceEvent.of("screen", type.type(), null));
                            return suspend(
                                    state,
                                    stack,
                                    env,
                                    effects,
                                    revision,
                                    path,
                                    type.type(),
                                    type.prepare(env, node.opts(), previous),
                                    error,
                                    attemptsLeft);
                        }
                        case Call call -> {
                            Handler handler = handlers.find(call.handler())
                                    .orElseThrow(() -> new IllegalStateException(
                                            call.source() + ": unknown handler " + call.handler()));
                            HandlerMeta meta = handler.meta();
                            switch (handler.invoke(new Invocation(env, input, call.args(), state.flowId()))) {
                                case Outcome.Fail f -> {
                                    failed = f.error();
                                    trace.add(TraceEvent.of("call", handler.name(), failed));
                                }
                                case Outcome.Ok ok -> {
                                    List<Effect> staged = effects(handler, ok);
                                    // Not retryable: a credential for someone else is not a typo.
                                    String mismatch = checkSubject(handler, ok, env);
                                    @Nullable List<String> amr = meta.credential() ? proven(handler, ok) : null;
                                    trace.add(new TraceEvent(
                                            "call", handler.name(), mismatch == null ? "ok" : mismatch, amr, null));
                                    if (mismatch != null) return end(state, env, effects, revision, mismatch);
                                    effects.addAll(staged);
                                    if (!ok.user().isEmpty()) env = env.withUser(ok.user());
                                    if (amr != null) {
                                        @Nullable
                                        String subject = meta.bindsSubject()
                                                ? ok.subject()
                                                : env.auth().subject();
                                        env = env.withVerified(subject, new Env.Verified(handler.name(), amr));
                                    }
                                }
                            }
                        }
                        case Hook hook -> {
                            switch (runHook(state, hook, env, null)) {
                                case HookOutput.None none -> {}
                                case HookOutput.Set set -> env = env.withVars(set.vars());
                                case HookOutput.Fail fail -> failed = fail.reason();
                                // Only the hook of a choose may pick
                                case HookOutput.Pick pick -> failed = HookOutput.HOOK_ERROR;
                            }
                            trace.add(TraceEvent.of("hook", hook.id(), failed == null ? "ok" : failed));
                        }
                        case Succeed succeed -> {
                            return end(state, env, effects, revision, succeeds(entry.kind(), env));
                        }
                        case Fail fail -> {
                            return end(state, env, effects, revision, fail.reason());
                        }
                    }
                }
            }
            if (failed != null) {
                // Unwind to the innermost retry with attempts left and rerun its body; without one,
                // the entry fails.
                Frame.@Nullable RetryExit again = null;
                while (again == null && !stack.isEmpty()) {
                    if (stack.removeLast() instanceof Frame.RetryExit r
                            && r.attempt() < ((Retry) resolve(root, r.path())).attempts()) {
                        again = r;
                    }
                }
                if (again == null) return end(state, env, effects, revision, failed);
                trace.add(TraceEvent.of("retry", null, failed));
                stack.add(new Frame.RetryExit(again.path(), again.attempt() + 1));
                stack.add(new Frame.Eval(child(again.path(), 0)));
                error = failed;
                attemptsLeft = ((Retry) resolve(root, again.path())).attempts() - again.attempt();
            }
        }
        return end(state, env, effects, revision, "incomplete");
    }

    /**
     * Applies the writes a successful entry staged, in order, before the host is told, so a write that
     * fails can still make the host reject. Only the request whose saved state ended the entry calls
     * this, so they are applied at most once. A write whose precondition no longer holds, or that
     * throws, fails the entry with its code; the rest are not applied. If the host cannot be told
     * after the writes, they stay (the entry succeeded) and the person starts the sign in again.
     */
    private Run commit(Run run, List<TraceEvent> trace) {
        if (!(run instanceof Ended e) || e.failure() != null) return run;
        for (Effect effect : e.writes()) {
            Handler handler = handlers.find(effect.handler())
                    .orElseThrow(() -> new IllegalStateException("write of unknown handler " + effect.handler()));
            @Nullable String failure;
            try {
                failure = handler.commit(effect);
            } catch (RuntimeException x) {
                failure = WRITE_ERROR;
            }
            if (failure != null) {
                trace.add(TraceEvent.of("commit", handler.name(), failure));
                return new Ended(e.state(), failure, List.of());
            }
        }
        return run;
    }

    /** The effects an ok outcome carries; only a {@code write} handler may return any, and only its own. */
    private static List<Effect> effects(Handler handler, Outcome.Ok ok) {
        if (ok.effects().isEmpty()) return List.of();
        if (handler.meta().effect() != HandlerMeta.EffectKind.WRITE) {
            throw new IllegalStateException(handler.name() + " returned effects but declares effect "
                    + handler.meta().effect().wireName());
        }
        for (Effect e : ok.effects()) {
            if (!e.handler().equals(handler.name())) {
                throw new IllegalStateException(handler.name() + " returned an effect of " + e.handler());
            }
        }
        return ok.effects();
    }

    /**
     * Runs a hook on a snapshot of everything but {@code auth}. A hook that throws, exceeds a limit,
     * or returns anything outside {@link HookOutput} fails with {@link HookOutput#HOOK_ERROR}.
     *
     * @param options ids of the enabled options, for the hook of a choose; else null
     */
    private HookOutput runHook(FlowState state, Hook hook, Env env, @Nullable List<String> options) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("request", env.request());
        ctx.put("client", env.client());
        ctx.put("host", env.host());
        ctx.put("tenant", env.tenant());
        ctx.put("user", env.user());
        ctx.put("vars", env.vars());
        if (options != null) ctx.put("options", options);
        try {
            return HookOutput.parse(hooks.run(state.tenantId(), hook, ctx))
                    .orElse(new HookOutput.Fail(HookOutput.HOOK_ERROR));
        } catch (HookException e) {
            return new HookOutput.Fail(HookOutput.HOOK_ERROR);
        }
    }

    /**
     * Why an entry that reached {@code succeed} still fails, or null. An authenticate entry must have
     * verified a credential for its subject, reached the assurance its tenant requires, and reached
     * the assurance the client asked for in {@code acr_values}.
     */
    private static @Nullable String succeeds(Entry.Kind kind, Env env) {
        if (kind != Entry.Kind.AUTHENTICATE) return null;
        // What counts is an amr proved, not a verification recorded: one with none proves nothing
        if (env.auth().subject() == null || env.auth().acr() == null) return "unauthenticated";
        if (!Acr.satisfies(env.auth().acr(), required(env.tenant()))) return "mfa_required";
        return Acr.satisfies(env.auth().acr(), env.host().get("acr_values")) ? null : "acr_not_satisfied";
    }

    /** The acr a tenant's settings require of every login, as {@code acr_values}. */
    private static List<String> required(Map<String, Object> tenant) {
        return Boolean.TRUE.equals(tenant.get("mfa_required")) ? List.of(Acr.TWO_FACTOR) : List.of();
    }

    /** A subject binding handler must name a subject, and must not switch to a different one. */
    private static @Nullable String checkSubject(Handler handler, Outcome.Ok ok, Env env) {
        if (!handler.meta().bindsSubject()) return null;
        if (ok.subject() == null || !handler.meta().credential()) {
            throw new IllegalStateException(handler.name() + " binds a subject but did not prove one");
        }
        String bound = env.auth().subject();
        return bound == null || bound.equals(ok.subject()) ? null : "subject_mismatch";
    }

    /** The amr an invocation proved, sorted; a handler may narrow its declared amr, never widen it. */
    private static List<String> proven(Handler handler, Outcome.Ok ok) {
        if (ok.amr() == null) return List.copyOf(new TreeSet<>(handler.meta().amr()));
        if (ok.amr().isEmpty() || !handler.meta().amr().containsAll(ok.amr())) {
            throw new IllegalStateException(handler.name() + " proved amr " + ok.amr() + " outside "
                    + handler.meta().amr());
        }
        return List.copyOf(new TreeSet<>(ok.amr()));
    }

    private static Optional<Screen> firstScreen(Node node) {
        return switch (node) {
            case Screen s -> Optional.of(s);
            case Seq s -> firstScreen(s.children().get(0));
            case Step s -> firstScreen(s.body());
            case When w -> firstScreen(w.body());
            case Retry r -> firstScreen(r.body());
            case Call c -> Optional.empty();
            case Hook h -> Optional.empty();
            case Choose c -> Optional.empty();
            case SetVar s -> Optional.empty();
            case Succeed s -> Optional.empty();
            case Fail f -> Optional.empty();
        };
    }

    private static Node resolve(Node root, List<Integer> path) {
        Node node = root;
        for (int i : path) {
            node = switch (node) {
                case Seq seq -> seq.children().get(i);
                case Choose choose -> choose.options().get(i).body();
                case Step step when i == 0 -> step.body();
                case When w when i == 0 -> w.body();
                case Retry r when i == 0 -> r.body();
                case Step step -> throw new IllegalStateException("bad path " + path);
                case When w -> throw new IllegalStateException("bad path " + path);
                case Retry r -> throw new IllegalStateException("bad path " + path);
                case Screen s -> throw new IllegalStateException("bad path " + path);
                case Call c -> throw new IllegalStateException("bad path " + path);
                case Hook h -> throw new IllegalStateException("bad path " + path);
                case SetVar s -> throw new IllegalStateException("bad path " + path);
                case Succeed s -> throw new IllegalStateException("bad path " + path);
                case Fail f -> throw new IllegalStateException("bad path " + path);
            };
        }
        return node;
    }

    private static List<Integer> child(List<Integer> path, int index) {
        List<Integer> out = new ArrayList<>(path);
        out.add(index);
        return out;
    }

    private static @Nullable String currentStep(List<Frame> stack) {
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (stack.get(i) instanceof Frame.StepExit exit) return exit.id();
        }
        return null;
    }

    private static Suspended suspend(
            FlowState state,
            List<Frame> stack,
            Env env,
            List<Effect> effects,
            long revision,
            List<Integer> path,
            String type,
            Map<String, Object> data,
            @Nullable String error,
            @Nullable Integer attemptsLeft) {
        var awaiting =
                new FlowState.Awaiting(path, type, currentStep(stack), randomId("n_"), data, error, attemptsLeft);
        return new Suspended(state(state, stack, env, awaiting, effects, revision));
    }

    /** The entry ended; its staged writes go with a success and are dropped with a failure. */
    private static Ended end(FlowState state, Env env, List<Effect> effects, long revision, @Nullable String failure) {
        return new Ended(
                state(state, List.of(), env, null, List.of(), revision),
                failure,
                failure == null ? effects : List.of());
    }

    private static FlowState state(
            FlowState s,
            List<Frame> stack,
            Env env,
            FlowState.@Nullable Awaiting awaiting,
            List<Effect> pending,
            long revision) {
        return new FlowState(
                s.flowId(),
                s.tenantId(),
                s.versionHash(),
                s.kind(),
                s.entry(),
                s.hostRef(),
                s.binder(),
                stack,
                env,
                awaiting,
                pending,
                revision);
    }

    // ---- edges ----

    /**
     * The flow, if it exists, {@code binder} is the one it was started with, and its version can
     * still run here; a wrong binder is refused exactly like an unknown flow.
     */
    private FlowState load(String flowId, String binder) {
        FlowState state = store.load(flowId)
                .filter(s -> MessageDigest.isEqual(
                        s.binder().getBytes(StandardCharsets.UTF_8),
                        hash(binder).getBytes(StandardCharsets.UTF_8)))
                .orElseThrow(() -> new FlowException(Code.FLOW_NOT_FOUND));
        if (state.awaiting() == null) {
            throw new FlowException(Code.FLOW_FINISHED);
        }
        String inconsistent = inconsistency(state, pinned(state));
        if (inconsistent != null) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "flow state does not fit its version ({0}); its flow ends",
                    inconsistent);
            throw new FlowException(Code.FLOW_NOT_FOUND);
        }
        return state;
    }

    /**
     * Why a stored state cannot be resumed on the version it pinned, or null if it can. Faye writes
     * every state, so this only finds a corrupt or hand edited row, or one a different build wrote
     * with the same state format: each fails its flow closed rather than failing mid run (ADR 0007,
     * F7). Checked: the entry and its kind, every frame against the node its path names, and the
     * screen the flow waits on with the data it was prepared with.
     */
    private @Nullable String inconsistency(FlowState state, FlowVersion version) {
        Entry entry = version.flow().entries().get(state.entry());
        if (entry == null || !EntrySelector.kind(state.kind()).equals(Optional.of(entry.kind()))) return "entry";
        if (state.revision() < 0) return "revision";
        Node root = entry.body();
        for (Frame frame : state.stack()) {
            Node node = resolveOrNull(root, frame.path());
            boolean fits = switch (frame) {
                case Frame.Eval eval -> node != null;
                case Frame.SeqNext next ->
                    node instanceof Seq seq
                            && next.next() >= 0
                            && next.next() <= seq.children().size();
                case Frame.StepExit exit ->
                    node instanceof Step step && step.id().equals(exit.id());
                case Frame.RetryExit exit ->
                    node instanceof Retry r && exit.attempt() >= 1 && exit.attempt() <= r.attempts();
                case Frame.Pick pick -> node instanceof Choose;
            };
            if (!fits) return "frame " + frame.getClass().getSimpleName();
        }
        FlowState.Awaiting awaiting = Objects.requireNonNull(state.awaiting());
        Node waiting = resolveOrNull(root, awaiting.path());
        boolean picker = ScreenRegistry.METHOD_PICKER.equals(awaiting.screen());
        if (picker
                ? !(waiting instanceof Choose)
                : !(waiting instanceof Screen s && s.type().equals(awaiting.screen()))) {
            return "awaiting";
        }
        if (picker && !(awaiting.data().get("options") instanceof List<?>)) return "picker options";
        if (Boolean.TRUE.equals(awaiting.data().get("register"))
                && state.env().auth().subject() == null) {
            return "registration without a subject";
        }
        if (ScreenRegistry.METHOD_PICKER.equals(awaiting.screen())
                != state.stack().stream()
                        .anyMatch(f -> f instanceof Frame.Pick && f.path().equals(awaiting.path()))) {
            return "picker frame";
        }
        return null;
    }

    private static @Nullable Node resolveOrNull(Node root, List<Integer> path) {
        try {
            return resolve(root, path);
        } catch (IllegalStateException | IndexOutOfBoundsException e) {
            return null;
        }
    }

    /**
     * The version a flow pinned. One that can no longer be read (an IR format this engine dropped)
     * or run (a handler or screen it lacks) fails the flow closed, as if it had expired: the person
     * starts the sign in again.
     */
    private FlowVersion pinned(FlowState state) {
        return versions.get(state.versionHash())
                .filter(this::runnable)
                .orElseThrow(() -> new FlowException(Code.FLOW_NOT_FOUND));
    }

    /** Layer L1 (ir-spec.md, Checks): everything the version names is in this engine's catalogs. */
    private boolean runnable(FlowVersion version) {
        return Verifier.verify(version.flow(), handlers, screens).isEmpty();
    }

    private ScreenSpec screen(String type) {
        return screens.find(type).orElseThrow(() -> new IllegalStateException("unknown screen " + type));
    }

    private ScreenView view(FlowState state) {
        var awaiting = state.awaiting();
        if (awaiting == null) throw new FlowException(Code.FLOW_FINISHED);
        Map<String, Object> screen =
                new LinkedHashMap<>(screen(awaiting.screen()).render(state.env(), awaiting.data()));
        if (awaiting.error() != null) screen.put("errors", List.of(Map.of("code", awaiting.error())));
        if (awaiting.attemptsLeft() != null) screen.put("attemptsLeft", awaiting.attemptsLeft());
        return new ScreenView(state.flowId(), awaiting.nonce(), awaiting.step(), screen);
    }

    private Redirect finish(HostRequest req, Ended ended) {
        FlowState s = ended.state();
        String failure = ended.failure();
        if (failure != null) {
            Redirect rejected = reported(s, () -> host.reject(req, "access_denied", failure));
            emit(s, List.of(TraceEvent.of("end", null, failure)));
            return rejected;
        }
        Env.Auth auth = s.env().auth();
        Redirect accepted = reported(s, () -> switch (s.kind()) {
            // succeeds() refused any login without a subject and a verified credential
            case LOGIN ->
                host.accept(
                        req,
                        new Authenticated(
                                Objects.requireNonNull(auth.subject()),
                                auth.amr(),
                                Objects.requireNonNull(auth.acr()),
                                Map.of("flowId", s.flowId(), "versionHash", s.versionHash())));
            case CONSENT -> host.accept(req, consented(req, s.env().user(), Objects.requireNonNull(auth.subject())));
            case LOGOUT -> throw new IllegalStateException("logout runs no flow entry");
        });
        emit(
                s,
                List.of(
                        s.kind() == HostKind.LOGIN
                                ? new TraceEvent("end", null, "succeed", auth.amr(), auth.acr())
                                : TraceEvent.of("end", null, "succeed")));
        return accepted;
    }

    /** Calls the host; if it throws, a preview's trace ends with {@code host_error} before the error propagates. */
    private Redirect reported(FlowState state, Supplier<Redirect> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            emit(state, List.of(TraceEvent.of("end", null, "host_error")));
            throw e;
        }
    }

    /** Hands a preview flow's events to {@link Previews}; other flows are not traced. */
    private void emit(FlowState state, List<TraceEvent> events) {
        if (!Previews.isPreview(state.env().client())) return;
        events.forEach(e -> previews.trace(state, e));
    }

    /** The finished login whose flow id the host hands back in the consent request's context, if this subject's. */
    private Optional<FlowState> linkedLogin(HostRequest req, String subject) {
        if (!(req.params().get("context") instanceof Map<?, ?> context
                && context.get("flowId") instanceof String flowId)) {
            return Optional.empty();
        }
        return store.load(flowId)
                .filter(s -> s.kind() == HostKind.LOGIN
                        && s.awaiting() == null
                        && s.env().auth().subject() != null
                        && s.env().auth().subject().equals(subject));
    }

    /**
     * Grants every requested scope and audience. ID token claims come from {@code user.*}, which
     * {@code identity.resolve} filled during login: {@code email} and {@code email_verified} with
     * scope email, {@code name} with scope profile. A preview client also gets {@code
     * faye_flow_id}, the login's flow id, so it can find the run's trace.
     */
    private static Consented consented(HostRequest req, Map<String, Object> user, String subject) {
        List<String> scopes = strings(req.params().get("requested_scope"));
        Map<String, Object> claims = new LinkedHashMap<>();
        if (scopes.contains("email")) {
            if (user.get("email") != null) claims.put("email", user.get("email"));
            if (user.get("email_verified") != null) claims.put("email_verified", user.get("email_verified"));
        }
        if (scopes.contains("profile") && user.get("name") != null) claims.put("name", user.get("name"));
        if (Previews.isPreview(req.client())
                && req.params().get("context") instanceof Map<?, ?> context
                && context.get("flowId") instanceof String flowId) {
            claims.put("faye_flow_id", flowId);
        }
        return new Consented(subject, scopes, strings(req.params().get("requested_access_token_audience")), claims);
    }

    private static List<String> strings(@Nullable Object list) {
        return list instanceof List<?> l
                ? l.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toList()
                : List.of();
    }

    private FlowVersion current(String tenant) {
        return versions.current(tenant)
                .orElseThrow(() -> new IllegalStateException("no flow published for tenant " + tenant));
    }

    private static String tenantOf(HostRequest req) {
        return req.client().get("metadata") instanceof Map<?, ?> meta && meta.get("tenant") instanceof String t
                ? t
                : DEFAULT_TENANT;
    }

    /** The binder as the flow state keeps it: hex SHA-256, so a stolen database does not hold binders. */
    private static String hash(String binder) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(binder.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A fresh binder, 32 random bytes, for a caller to hand the browser. */
    public static String newBinder() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String randomId(String prefix) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
