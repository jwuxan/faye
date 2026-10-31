package faye.engine;

import faye.engine.FlowException.Code;
import faye.ir.Call;
import faye.ir.Choose;
import faye.ir.Fail;
import faye.ir.Hook;
import faye.ir.Node;
import faye.ir.Retry;
import faye.ir.Screen;
import faye.ir.Seq;
import faye.ir.SetVar;
import faye.ir.Step;
import faye.ir.Succeed;
import faye.ir.When;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Small step interpreter over an explicit continuation stack. A flow runs until it reaches a
 * screen, at which point its {@link FlowState} is persisted and the screen returned; a submission
 * reloads the state and continues. Nothing about a flow lives in this object between requests.
 */
public final class Engine {

    public static final String DEFAULT_TENANT = "default";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final FlowVersions versions;
    private final HandlerRegistry handlers;
    private final FlowStore store;
    private final FlowHost host;
    private final TenantPolicies tenants;
    private final HookRunner hooks;
    private final Previews previews;

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
        this.versions = versions;
        this.handlers = handlers;
        this.store = store;
        this.host = host;
        this.tenants = tenants;
        this.hooks = hooks;
        this.previews = previews;
    }

    /**
     * Begins a flow for a challenge. A login runs the tenant's current version, or for a preview
     * client the version {@link Previews} gives. A consent runs the version its login ran, found
     * through the {@code flowId} the login put in Hydra's context, and sees that login's {@code
     * user} and {@code auth}.
     */
    public EngineResponse start(HostKind kind, String challenge, Map<String, Object> request) {
        HostRequest req = host.load(kind, challenge);
        @Nullable String subject = req.hydra().get("subject") instanceof String s && !s.isEmpty() ? s : null;
        boolean skip = Boolean.TRUE.equals(req.hydra().get("skip"));
        boolean preview = Previews.isPreview(req.client());
        String tenant = tenantOf(req);
        FlowVersion version;
        Map<String, Object> user = Map.of();
        Map<String, Object> settings = tenants.get(tenant).env();
        Env.Auth auth = Env.Auth.NONE;

        switch (kind) {
            case LOGIN -> {
                Optional<FlowVersion> entry = preview ? previews.version(tenant) : Optional.of(current(tenant));
                if (entry.isEmpty()) return host.reject(req, "access_denied", "preview_unavailable");
                version = entry.get();
                if (skip && subject != null) {
                    // Hydra remembers who, not how: a remembered session counts as one factor.
                    if (Acr.satisfies(Acr.ONE_FACTOR, req.hydra().get("acr_values"))
                            && Acr.satisfies(Acr.ONE_FACTOR, required(settings))) {
                        return host.accept(req, new Authenticated(subject, List.of(), Acr.ONE_FACTOR, Map.of()));
                    }
                    // Step up: run the flow, but only for the subject Hydra already knows.
                    auth = new Env.Auth(subject, List.of());
                }
            }
            case CONSENT -> {
                if (subject == null) throw new IllegalStateException("consent request without a subject");
                Optional<FlowState> login = linkedLogin(req, subject);
                if (login.isPresent()) {
                    tenant = login.get().tenantId();
                    version = versions.get(login.get().versionHash()).orElseGet(() -> current(tenantOf(req)));
                    user = login.get().env().user();
                    settings = login.get().env().tenant();
                    auth = login.get().env().auth();
                } else {
                    Optional<FlowVersion> entry = preview ? previews.version(tenant) : Optional.of(current(tenant));
                    if (entry.isEmpty()) return host.reject(req, "access_denied", "preview_unavailable");
                    version = entry.get();
                    auth = new Env.Auth(subject, List.of());
                }
                if (skip || !version.flow().sections().containsKey(kind.section())) {
                    return host.accept(req, consented(req, user, subject));
                }
            }
            case LOGOUT -> throw new IllegalArgumentException("logout is not a flow section");
            default -> throw new IllegalArgumentException(kind.toString());
        }
        if (!version.flow().sections().containsKey(kind.section())) {
            throw new IllegalStateException(version.flow().id() + " has no " + kind.section() + " section");
        }
        if (preview) {
            Map<String, Object> overridden = new LinkedHashMap<>(request);
            overridden.putAll(previews.request(tenant));
            request = overridden;
        }
        Env env = new Env(request, req.client(), settings, req.hydra(), user, Map.of(), auth);
        FlowState initial = new FlowState(
                randomId("f_"),
                tenant,
                version.hash(),
                kind,
                challenge,
                List.of(new Frame.Eval(List.of())),
                env,
                null,
                0);

        List<TraceEvent> trace = new ArrayList<>();
        trace.add(TraceEvent.of("start", version.hash(), kind.section()));
        Run run = run(initial, version, Submission.NONE, 0, trace);
        if (run instanceof Suspended s) store.create(s.state());
        emit(run.state(), trace);
        return switch (run) {
            case Suspended s -> view(s.state());
            case Ended e -> finish(req, e);
        };
    }

    /** The screen a flow is currently waiting on. */
    public ScreenView view(String flowId) {
        return view(load(flowId));
    }

    /**
     * Answers the current screen. A stale nonce (already answered, or not this screen) and a lost
     * race against a concurrent submit are both refused without changing the stored state.
     */
    public EngineResponse submit(String flowId, String nonce, Submission input) {
        FlowState state = load(flowId);
        FlowState.Awaiting awaiting = Objects.requireNonNull(state.awaiting(), "load refuses finished flows");
        if (!awaiting.nonce().equals(nonce)) {
            throw new FlowException(Code.STALE_NONCE);
        }
        if (!ScreenType.of(awaiting.screen()).orElseThrow().accepts(input, awaiting.data())) {
            throw new FlowException(Code.INVALID_SUBMISSION);
        }
        FlowVersion version = versions.get(state.versionHash())
                .orElseThrow(() -> new IllegalStateException("pinned version missing: " + state.versionHash()));

        List<TraceEvent> trace = new ArrayList<>();
        Run run = run(state, version, input.withContext(awaiting.data()), state.revision() + 1, trace);
        // Save before talking to the host: only the submit that wins the revision race may accept.
        if (!store.replace(run.state())) {
            throw new FlowException(Code.CONFLICT);
        }
        emit(run.state(), trace);
        return switch (run) {
            case Suspended s -> view(s.state());
            case Ended e -> finish(host.load(state.kind(), state.hostRef()), e);
        };
    }

    // ---- interpreter ----

    private sealed interface Run permits Suspended, Ended {
        FlowState state();
    }

    private record Suspended(FlowState state) implements Run {}

    /** @param failure null when the section succeeded */
    private record Ended(FlowState state, @Nullable String failure) implements Run {}

    /** @param trace collects what the run does, for preview flows */
    private Run run(FlowState state, FlowVersion version, Submission input, long revision, List<TraceEvent> trace) {
        Node root = Objects.requireNonNull(version.flow().sections().get(state.section()), "start checked the section");
        List<Frame> stack = new ArrayList<>(state.stack());
        Env env = state.env();
        FlowState.@Nullable Awaiting answered = state.awaiting();
        // An answer this submission carries for a later screen (passkey autofill on identifier).
        @Nullable
        ScreenType hosted = answered == null
                ? null
                : ScreenType.of(answered.screen()).orElseThrow().hosted(input).orElse(null);
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
                                if (!(choose.options().get(i).body() instanceof When w)
                                        || Predicates.test(w.test(), env)) {
                                    enabled.add(i);
                                }
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
                                        && ScreenType.of(first.get().type())
                                                .orElseThrow()
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
                                trace.add(TraceEvent.of("screen", ScreenType.METHOD_PICKER.wireName(), null));
                                return suspend(
                                        state,
                                        stack,
                                        env,
                                        revision,
                                        path,
                                        ScreenType.METHOD_PICKER,
                                        data,
                                        error,
                                        attemptsLeft);
                            }
                        }
                        case Screen screen -> {
                            ScreenType type = ScreenType.of(screen.type())
                                    .orElseThrow(() -> new IllegalStateException(
                                            screen.source() + ": unknown screen " + screen.type()));
                            if (hosted != null && type.answeredBy(hosted, screen.opts())) {
                                hosted = null;
                                break;
                            }
                            Map<String, Object> previous = answered != null
                                            && answered.path().equals(path)
                                            && answered.screen().equals(type.wireName())
                                    ? answered.data()
                                    : Map.of();
                            trace.add(TraceEvent.of("screen", type.wireName(), null));
                            return suspend(
                                    state,
                                    stack,
                                    env,
                                    revision,
                                    path,
                                    type,
                                    type.prepare(env, screen.opts(), previous),
                                    error,
                                    attemptsLeft);
                        }
                        case Call call -> {
                            Handler handler = handlers.find(call.handler())
                                    .orElseThrow(() -> new IllegalStateException(
                                            call.source() + ": unknown handler " + call.handler()));
                            switch (handler.invoke(env, input, call.args())) {
                                case Outcome.Fail f -> {
                                    failed = f.error();
                                    trace.add(TraceEvent.of("call", handler.name(), failed));
                                }
                                case Outcome.Ok ok -> {
                                    // Not retryable: a credential for someone else is not a typo.
                                    String mismatch = checkSubject(handler, ok, env);
                                    @Nullable List<String> amr = handler.amr().isEmpty() ? null : proven(handler, ok);
                                    trace.add(new TraceEvent(
                                            "call", handler.name(), mismatch == null ? "ok" : mismatch, amr, null));
                                    if (mismatch != null) return end(state, env, revision, mismatch);
                                    if (!ok.user().isEmpty()) env = env.withUser(ok.user());
                                    if (amr != null) {
                                        @Nullable
                                        String subject = handler.bindsSubject()
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
                            return end(state, env, revision, succeeds(state.kind(), env));
                        }
                        case Fail fail -> {
                            return end(state, env, revision, fail.reason());
                        }
                    }
                }
            }
            if (failed != null) {
                // Unwind to the innermost retry with attempts left and rerun its body; without one,
                // the section fails.
                Frame.@Nullable RetryExit again = null;
                while (again == null && !stack.isEmpty()) {
                    if (stack.removeLast() instanceof Frame.RetryExit r
                            && r.attempt() < ((Retry) resolve(root, r.path())).attempts()) {
                        again = r;
                    }
                }
                if (again == null) return end(state, env, revision, failed);
                trace.add(TraceEvent.of("retry", null, failed));
                stack.add(new Frame.RetryExit(again.path(), again.attempt() + 1));
                stack.add(new Frame.Eval(child(again.path(), 0)));
                error = failed;
                attemptsLeft = ((Retry) resolve(root, again.path())).attempts() - again.attempt();
            }
        }
        return end(state, env, revision, "incomplete");
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
        ctx.put("hydra", env.hydra());
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
     * Why a section that reached {@code succeed} still fails, or null. A login must have verified a
     * credential for its subject, reached the assurance its tenant requires, and reached the
     * assurance the client asked for in {@code acr_values}.
     */
    private static @Nullable String succeeds(HostKind kind, Env env) {
        if (kind != HostKind.LOGIN) return null;
        if (env.auth().subject() == null || env.auth().verified().isEmpty()) return "unauthenticated";
        if (!Acr.satisfies(env.auth().acr(), required(env.tenant()))) return "mfa_required";
        return Acr.satisfies(env.auth().acr(), env.hydra().get("acr_values")) ? null : "acr_not_satisfied";
    }

    /** The acr a tenant's settings require of every login, as {@code acr_values}. */
    private static List<String> required(Map<String, Object> tenant) {
        return Boolean.TRUE.equals(tenant.get("mfa_required")) ? List.of(Acr.TWO_FACTOR) : List.of();
    }

    /** A subject binding handler must name a subject, and must not switch to a different one. */
    private static @Nullable String checkSubject(Handler handler, Outcome.Ok ok, Env env) {
        if (!handler.bindsSubject()) return null;
        if (ok.subject() == null || handler.amr().isEmpty()) {
            throw new IllegalStateException(handler.name() + " binds a subject but did not prove one");
        }
        String bound = env.auth().subject();
        return bound == null || bound.equals(ok.subject()) ? null : "subject_mismatch";
    }

    /** The amr an invocation proved, sorted; a handler may narrow its declared amr, never widen it. */
    private static List<String> proven(Handler handler, Outcome.Ok ok) {
        if (ok.amr() == null) return List.copyOf(new TreeSet<>(handler.amr()));
        if (ok.amr().isEmpty() || !handler.amr().containsAll(ok.amr())) {
            throw new IllegalStateException(handler.name() + " proved amr " + ok.amr() + " outside " + handler.amr());
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
            long revision,
            List<Integer> path,
            ScreenType type,
            Map<String, Object> data,
            @Nullable String error,
            @Nullable Integer attemptsLeft) {
        var awaiting = new FlowState.Awaiting(
                path, type.wireName(), currentStep(stack), randomId("n_"), data, error, attemptsLeft);
        return new Suspended(state(state, stack, env, awaiting, revision));
    }

    private static Ended end(FlowState state, Env env, long revision, @Nullable String failure) {
        return new Ended(state(state, List.of(), env, null, revision), failure);
    }

    private static FlowState state(
            FlowState s, List<Frame> stack, Env env, FlowState.@Nullable Awaiting awaiting, long revision) {
        return new FlowState(
                s.flowId(), s.tenantId(), s.versionHash(), s.kind(), s.hostRef(), stack, env, awaiting, revision);
    }

    // ---- edges ----

    private FlowState load(String flowId) {
        FlowState state = store.load(flowId).orElseThrow(() -> new FlowException(Code.FLOW_NOT_FOUND));
        if (state.awaiting() == null) {
            throw new FlowException(Code.FLOW_FINISHED);
        }
        return state;
    }

    private static ScreenView view(FlowState state) {
        var awaiting = state.awaiting();
        if (awaiting == null) throw new FlowException(Code.FLOW_FINISHED);
        Map<String, Object> screen = new LinkedHashMap<>(
                ScreenType.of(awaiting.screen()).orElseThrow().render(state.env(), awaiting.data()));
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
            case LOGOUT -> throw new IllegalStateException("logout is not a flow section");
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

    /** The finished login whose flow id Hydra hands back in the consent request's context, if it is this subject's. */
    private Optional<FlowState> linkedLogin(HostRequest req, String subject) {
        if (!(req.hydra().get("context") instanceof Map<?, ?> context
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
        List<String> scopes = strings(req.hydra().get("requested_scope"));
        Map<String, Object> claims = new LinkedHashMap<>();
        if (scopes.contains("email")) {
            if (user.get("email") != null) claims.put("email", user.get("email"));
            if (user.get("email_verified") != null) claims.put("email_verified", user.get("email_verified"));
        }
        if (scopes.contains("profile") && user.get("name") != null) claims.put("name", user.get("name"));
        if (Previews.isPreview(req.client())
                && req.hydra().get("context") instanceof Map<?, ?> context
                && context.get("flowId") instanceof String flowId) {
            claims.put("faye_flow_id", flowId);
        }
        return new Consented(subject, scopes, strings(req.hydra().get("requested_access_token_audience")), claims);
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

    private static String randomId(String prefix) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
