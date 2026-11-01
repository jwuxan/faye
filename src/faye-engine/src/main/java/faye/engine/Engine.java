package faye.engine;

import faye.engine.FlowException.Code;
import faye.engine.spi.Authenticated;
import faye.engine.spi.Completion;
import faye.engine.spi.Consented;
import faye.engine.spi.EngineResponse;
import faye.engine.spi.FlowHost;
import faye.engine.spi.FlowStore;
import faye.engine.spi.FlowVersions;
import faye.engine.spi.HostKind;
import faye.engine.spi.HostParams;
import faye.engine.spi.HostRequest;
import faye.engine.spi.Redirect;
import faye.engine.spi.ScreenView;
import faye.hooks.HookException;
import faye.ir.Call;
import faye.ir.Choose;
import faye.ir.Entry;
import faye.ir.Fail;
import faye.ir.Hook;
import faye.ir.Node;
import faye.ir.NodePaths;
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
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Small step interpreter over an explicit continuation stack; the IR specification is the
 * normative semantics it implements. A flow runs until it reaches a screen, at which point its
 * {@link FlowState} is persisted and the screen returned; a submission reloads the state and
 * continues. Nothing about a flow lives in this object between requests.
 *
 * <p>The engine knows no authorization server and renders nothing. It talks to the system it runs
 * flows for only through {@link FlowHost}, and to everything else through the SPIs it is built with
 * ({@link #builder()}): {@link FlowVersions}, {@link FlowStore}, {@link HandlerRegistry}, {@link
 * ScreenRegistry} with its {@link CredentialIssuer}s, {@link TenantPolicies}, {@link HookRunner} and
 * {@link Previews}.
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

    /** Why an entry ends when its suspending state is too large to keep; terminal, never retried. */
    public static final String LIMIT_EXCEEDED = "limit_exceeded";

    /**
     * Why a step fails when a screen's {@link CredentialIssuer} could not issue (an upstream
     * failure or timeout). It fails like a handler failure: a retry may rerun the step, else the
     * entry fails.
     */
    public static final String ISSUER_ERROR = "issuer_error";

    /**
     * The payload field that carries "keep me signed in" on a screen that offers it ({@link
     * ScreenSpec#offersRemember}); the engine takes it off before the screen judges the answer.
     */
    public static final String REMEMBER = "remember";

    /** Why a flow is reported failed when the host threw as it was told how the flow ended. */
    public static final String HOST_ERROR = "host_error";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final FlowVersions versions;
    private final HandlerRegistry handlers;
    private final FlowStore store;
    private final FlowHost host;
    private final TenantPolicies tenants;
    private final HookRunner hooks;
    private final Previews previews;
    private final Failures failures;
    private final ScreenRegistry screens;
    private final Clock clock;

    private Engine(Builder b) {
        this.versions = required(b.versions, "versions");
        this.handlers = required(b.handlers, "handlers");
        this.store = required(b.store, "store");
        this.host = required(b.host, "host");
        this.screens = required(b.screens, "screens");
        this.tenants = b.tenants;
        this.hooks = b.hooks;
        this.previews = b.previews;
        this.clock = b.clock;
        this.failures = b.failures;
    }

    /**
     * How an embedder assembles an engine from its SPIs. {@code versions}, {@code handlers}, {@code
     * screens}, {@code store} and {@code host} are required; tenants default to {@link
     * TenantPolicy#DEFAULT} for all, hooks to {@link HookRunner#NONE}, previews to {@link
     * Previews#NONE}, failures to {@link Failures#NONE}, the clock to the system clock. Registration
     * is explicit: the engine runs
     * exactly the handlers, screens and issuers it is given, and discovers nothing.
     */
    public static Builder builder() {
        return new Builder();
    }

    /** See {@link Engine#builder()}. */
    public static final class Builder {
        private @Nullable FlowVersions versions;
        private @Nullable HandlerRegistry handlers;
        private @Nullable FlowStore store;
        private @Nullable FlowHost host;
        private @Nullable ScreenRegistry screens;
        private TenantPolicies tenants = TenantPolicies.of(Map.of());
        private HookRunner hooks = HookRunner.NONE;
        private Previews previews = Previews.NONE;
        private Clock clock = Clock.systemUTC();
        private Failures failures = Failures.NONE;

        private Builder() {}

        /** What the flow settings' limits are measured on (ADR 0035); the system clock by default. */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /** Where published versions are read. */
        public Builder versions(FlowVersions versions) {
            this.versions = versions;
            return this;
        }

        /** The handlers flows may call. */
        public Builder handlers(HandlerRegistry handlers) {
            this.handlers = handlers;
            return this;
        }

        /** Where suspended flows are kept. */
        public Builder store(FlowStore store) {
            this.store = store;
            return this;
        }

        /** The system flows run for. */
        public Builder host(FlowHost host) {
            this.host = host;
            return this;
        }

        /** The screens flows may show, with their credential issuers. */
        public Builder screens(ScreenRegistry screens) {
            this.screens = screens;
            return this;
        }

        /** Looked up when a flow starts. */
        public Builder tenants(TenantPolicies tenants) {
            this.tenants = tenants;
            return this;
        }

        /** Policy per tenant id; a tenant not listed gets {@link TenantPolicy#DEFAULT}. */
        public Builder tenants(Map<String, TenantPolicy> tenants) {
            return tenants(TenantPolicies.of(tenants));
        }

        /** Runs the hooks flows pin. */
        public Builder hooks(HookRunner hooks) {
            this.hooks = hooks;
            return this;
        }

        /** What preview clients run, and where their traces go. */
        public Builder previews(Previews previews) {
            this.previews = previews;
            return this;
        }

        /** Told where every run that ends abnormally ended (ADR 0027). */
        public Builder failures(Failures failures) {
            this.failures = failures;
            return this;
        }

        public Engine build() {
            return new Engine(this);
        }
    }

    private static <T> T required(@Nullable T value, String what) {
        if (value == null) throw new IllegalStateException("an engine needs " + what);
        return value;
    }

    /**
     * Begins a flow for a host request. A login runs the tenant's current version, or for a preview
     * client the version {@link Previews} gives. A consent runs the version its login ran, found
     * through the {@code flowId} the login put in the host's context, and sees that login's {@code
     * user} and {@code auth}.
     *
     * @param ref the host's id for the request, handed to {@link FlowHost#load}
     * @param request what the caller knows of the request that started the flow, read as {@code
     *     request.*} (ip, user agent)
     * @param binder the secret the caller gives the browser; every later view and submit must
     *     present it
     * @return the first screen, or the host's completion if the flow ended at once
     */
    public EngineResponse start(HostKind kind, String ref, Map<String, Object> request, String binder) {
        HostRequest req = host.load(kind, ref);
        @Nullable String subject = req.params().subject();
        boolean skip = req.params().skip();
        boolean preview = req.preview();
        String tenant = req.tenantOrDefault();
        FlowVersion version;
        Map<String, Object> user = Map.of();
        Map<String, Object> settings = tenants.get(tenant).env();
        Env.Auth auth = Env.Auth.NONE;

        switch (kind) {
            case LOGIN -> {
                Optional<FlowVersion> found = preview ? previews.version(tenant) : versions.current(tenant);
                // A client naming a tenant with no published flow is refused, not a 5xx (ADR 0007)
                if (found.isEmpty()) {
                    return host.reject(req, "access_denied", preview ? "preview_unavailable" : FLOW_UNAVAILABLE);
                }
                version = found.get();
                if (skip && subject != null) {
                    // The host remembers who, not how: a remembered session counts as one factor.
                    if (Acr.satisfies(Acr.ONE_FACTOR, req.params().acrValues())
                            && Acr.satisfies(Acr.ONE_FACTOR, required(settings))) {
                        return host.accept(req, new Authenticated(subject, List.of(), Acr.ONE_FACTOR, Map.of()));
                    }
                    // Step up: run the flow, but only for the subject the host already knows.
                    auth = new Env.Auth(subject, List.of());
                }
            }
            case CONSENT -> {
                // A consent is for someone; a host that names no one gets a refusal, not a 5xx (F11)
                if (subject == null) return host.reject(req, "access_denied", "no_subject");
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
                    Optional<FlowVersion> found = preview ? previews.version(tenant) : versions.current(tenant);
                    if (found.isEmpty()) {
                        return host.reject(req, "access_denied", preview ? "preview_unavailable" : FLOW_UNAVAILABLE);
                    }
                    version = found.get();
                    auth = new Env.Auth(subject, List.of());
                }
                // Granted without a screen: remembered, or a version with no consent entry at all
                if (skip || version.flow().entriesOf(Entry.Kind.CONSENT).isEmpty()) {
                    return host.accept(req, consented(req, user, subject));
                }
            }
            // The phone's side of a QR code sign in (ADR 0032): the tenant's current version, never a
            // remembered session, which would approve with nothing proved on this device
            case APPROVE -> {
                Optional<FlowVersion> found = preview ? previews.version(tenant) : versions.current(tenant);
                if (found.isEmpty()) {
                    return host.reject(req, "access_denied", preview ? "preview_unavailable" : FLOW_UNAVAILABLE);
                }
                version = found.get();
            }
            case LOGOUT -> throw new IllegalArgumentException("logout runs no flow entry");
            default -> throw new IllegalArgumentException(kind.toString());
        }
        // A version that names a handler or screen this engine lacks, or has no entry for the
        // request, never runs here: the checker refuses both at publish, so this only happens when
        // the catalogs changed since.
        Optional<String> entry = EntrySelector.select(version.flow(), kind);
        if (entry.isEmpty() || !runnable(version)) return host.reject(req, "access_denied", FLOW_UNAVAILABLE);
        if (preview) {
            Map<String, Object> overridden = new LinkedHashMap<>(request);
            overridden.putAll(previews.request(tenant));
            request = overridden;
        }
        Env env = new Env(request, req.client(), settings, req.params().toEnv(), user, Map.of(), auth);
        FlowState initial = new FlowState(
                randomId("f_"),
                tenant,
                version.hash(),
                kind,
                entry.get(),
                ref,
                hash(binder),
                List.of(new Frame.Eval(List.of())),
                env,
                null,
                List.of(),
                0,
                preview,
                kind == HostKind.CONSENT ? user : Map.of(),
                false,
                tenants.get(tenant).flow(EntrySelector.kind(kind).orElseThrow()).start(clock.millis()));

        List<TraceEvent> trace = new ArrayList<>();
        trace.add(TraceEvent.of("start", version.hash(), entry.get()));
        Run run = run(initial, version, Submission.NONE, 0, trace);
        // Only a flow that waits is stored; one that ended at once has nothing to resume
        if (run instanceof Suspended) store.create(run.state());
        run = commit(run, trace);
        emit(run.state(), trace);
        return switch (run) {
            case Suspended s -> view(s.state(), version);
            case Ended e -> finish(req, e);
        };
    }

    /** The screen a flow is currently waiting on. */
    public ScreenView view(String flowId, String binder) {
        FlowState state = load(flowId, binder);
        return view(state, pinned(state));
    }

    /**
     * Answers the current screen. A stale nonce (already answered, or not this screen) and a lost
     * race against a concurrent submit are both refused without changing the stored state or any
     * account: staged writes are applied only by the submit whose saved state ended the entry
     * successfully.
     */
    public EngineResponse submit(String flowId, String binder, String nonce, Submission input) {
        FlowState loaded = load(flowId, binder);
        FlowState.Awaiting awaiting = Objects.requireNonNull(loaded.awaiting(), "load refuses finished flows");
        if (!awaiting.nonce().equals(nonce)) {
            throw new FlowException(Code.STALE_NONCE);
        }
        ScreenSpec spec = screen(awaiting.screen());
        // "Keep me signed in" rides along with the answer of a screen that offered it (ADR 0033):
        // the person's choice, recorded on the flow; the rest of the payload is the screen's answer
        Submission answer = input;
        FlowState state = loaded;
        if (spec.offersRemember(awaiting.data()) && input.payload().containsKey(REMEMBER)) {
            if (!(input.payload().get(REMEMBER) instanceof Boolean remember)) {
                throw new FlowException(Code.INVALID_SUBMISSION);
            }
            Map<String, Object> rest = new LinkedHashMap<>(input.payload());
            rest.remove(REMEMBER);
            answer = new Submission(rest);
            state = loaded.withRemember(remember);
        }
        if (!spec.accepts(answer, awaiting.data())) {
            throw new FlowException(Code.INVALID_SUBMISSION);
        }
        // The idle timeout counts from the last answer the engine took (ADR 0035)
        state = state.touched(clock.millis());
        FlowVersion version = pinned(state);

        List<TraceEvent> trace = new ArrayList<>();
        Run run = run(state, version, answer.withContext(awaiting.data()), state.revision() + 1, trace);
        // Save before any lasting change and before talking to the host: only the submit that wins
        // the revision race may apply staged writes or accept.
        if (!store.replace(run.state())) return lost(state);
        run = commit(run, trace);
        emit(run.state(), trace);
        return switch (run) {
            case Suspended s -> view(s.state(), version);
            case Ended e -> finish(host.load(state.kind(), state.hostRef()), e);
        };
    }

    /**
     * Takes an action the waiting screen declares (ADR 0032): its {@link CredentialIssuer} answers
     * it, no handler runs, no attempt is spent and the flow stays on the same screen, which is shown
     * again with what the issuer left (a resent code, or {@code send_limited}). Saved under the same
     * compare and set as an answer, so of two concurrent requests one is refused with {@code
     * conflict}.
     *
     * @throws FlowException {@code unknown_action} for an action the screen does not declare or
     *     input it does not take
     */
    public ScreenView act(String flowId, String binder, String nonce, String action, Map<String, Object> input) {
        FlowState state = load(flowId, binder);
        FlowState.Awaiting awaiting = Objects.requireNonNull(state.awaiting(), "load refuses finished flows");
        if (!awaiting.nonce().equals(nonce)) {
            throw new FlowException(Code.STALE_NONCE);
        }
        ScreenSpec spec = screen(awaiting.screen());
        Optional<CredentialIssuer> issuer = screens.issuer(awaiting.screen());
        if (!spec.actions().contains(action)
                || issuer.isEmpty()
                || !spec.acceptsAction(action, input, awaiting.data())) {
            throw new FlowException(Code.UNKNOWN_ACTION);
        }
        FlowVersion version = pinned(state);
        Entry entry = Objects.requireNonNull(version.flow().entries().get(state.entry()), "the state fits its version");
        Map<String, Object> opts =
                resolve(entry.body(), awaiting.path()) instanceof Screen node ? node.opts() : Map.of();
        Map<String, Object> data = new LinkedHashMap<>(awaiting.data());
        @Nullable String error;
        try {
            CredentialIssuer.Acted acted =
                    issuer.get().act(state.env(), context(state), opts, awaiting.data(), action, Map.copyOf(input));
            data.putAll(acted.data());
            error = acted.error();
        } catch (RuntimeException e) {
            error = ISSUER_ERROR;
        }
        FlowState next = state.acted(data, error);
        if (!store.replace(next)) throw new FlowException(Code.CONFLICT);
        return view(next, version);
    }

    private static CredentialIssuer.Context context(FlowState state) {
        return new CredentialIssuer.Context(state.flowId(), state.tenantId(), state.preview(), state.hostRef());
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
        return view(now, pinned(now));
    }

    // ---- interpreter ----

    private sealed interface Run permits Suspended, Ended {
        FlowState state();
    }

    private record Suspended(FlowState state) implements Run {}

    /**
     * A run that finished.
     *
     * @param failure null when the entry succeeded
     * @param writes the writes the entry staged, to apply because it succeeded; empty on a failure
     * @param at the node the entry ended at
     * @param step the innermost step around it, or null
     */
    private record Ended(
            FlowState state,
            @Nullable String failure,
            List<Effect> writes,
            Site at,
            @Nullable String step) implements Run {
        Ended withFailure(String code) {
            return new Ended(state, code, List.of(), at, step);
        }
    }

    /**
     * Runs a suspended flow's answer.
     *
     * @param trace collects what the run does, for preview flows
     */
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
        // How much this run spent on hooks: the per run backstops the static limits imply.
        HookBudget budget = new HookBudget();

        while (!stack.isEmpty()) {
            Frame frame = stack.removeLast();
            List<Integer> path = frame.path();
            Site at = new Site(state.entry(), path);
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
                            trace.add(TraceEvent.of("option", method, "user", site(state, child(path, i))));
                            stack.add(new Frame.Eval(child(path, i)));
                        }
                    }
                }
                case Frame.Eval eval -> {
                    switch (resolve(root, path)) {
                        case Seq seq -> stack.add(new Frame.SeqNext(path, 0));
                        case Step step -> {
                            trace.add(TraceEvent.of("step", step.id(), null, at));
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
                                switch (runHook(state, choose.pick(), env, ids, budget)) {
                                    case HookOutput.None none -> {}
                                    // The hook of a choose only picks; vars are set by hooks in steps
                                    case HookOutput.Set set -> failed = HookOutput.HOOK_ERROR;
                                    case HookOutput.Fail fail -> failed = fail.reason();
                                    case HookOutput.Pick pick -> {
                                        int index = ids.indexOf(pick.option());
                                        if (index < 0) {
                                            failed = HookOutput.HOOK_ERROR;
                                        } else {
                                            picked = enabled.get(index);
                                        }
                                    }
                                }
                                trace.add(
                                        TraceEvent.of("hook", choose.pick().id(), failed == null ? "ok" : failed, at));
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
                                        "option",
                                        choose.options().get(chosen).id(),
                                        by,
                                        site(state, child(path, chosen))));
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
                                trace.add(TraceEvent.of("screen", ScreenRegistry.METHOD_PICKER, null, at));
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
                            @Nullable
                            Map<String, Object> data = prepare(type, env, context(state), node.opts(), previous);
                            if (data == null) {
                                failed = ISSUER_ERROR;
                                trace.add(TraceEvent.of("screen", type.type(), ISSUER_ERROR, at));
                                break;
                            }
                            trace.add(TraceEvent.of("screen", type.type(), null, at));
                            return suspend(
                                    state, stack, env, effects, revision, path, type.type(), data, error, attemptsLeft);
                        }
                        case Call call -> {
                            Handler handler = handlers.find(call.handler())
                                    .orElseThrow(() -> new IllegalStateException(
                                            "unknown handler " + call.handler() + " at " + at));
                            HandlerMeta meta = handler.meta();
                            switch (handler.invoke(new Invocation(
                                    env, input, call.args(), state.flowId(), state.tenantId(), state.preview()))) {
                                case Outcome.Fail f -> {
                                    failed = f.error();
                                    trace.add(TraceEvent.of("call", handler.name(), failed, at));
                                }
                                case Outcome.Ok ok -> {
                                    List<Effect> staged = effects(handler, ok);
                                    // Not retryable: a credential for someone else is not a typo.
                                    String mismatch = checkSubject(handler, ok, env);
                                    @Nullable List<String> amr = meta.credential() ? proven(handler, ok) : null;
                                    trace.add(new TraceEvent(
                                            "call", handler.name(), mismatch == null ? "ok" : mismatch, amr, null, at));
                                    if (mismatch != null) {
                                        return end(state, env, effects, revision, mismatch, at, currentStep(stack));
                                    }
                                    effects.addAll(staged);
                                    // An identifying handler names the person afresh: nothing an
                                    // earlier attempt learned about someone else may stay (O16)
                                    if (meta.identifies()) {
                                        env = env.withUserReplaced(ok.user());
                                    } else if (!ok.user().isEmpty()) {
                                        env = env.withUser(ok.user());
                                    }
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
                            switch (runHook(state, hook, env, null, budget)) {
                                case HookOutput.None none -> {}
                                case HookOutput.Set set -> {
                                    Set<String> names = new HashSet<>(env.vars().keySet());
                                    names.addAll(set.vars().keySet());
                                    // The whole set fails, or the cap is a way to keep writing
                                    if (names.size() > FlowLimits.MAX_VARS) {
                                        failed = HookOutput.HOOK_ERROR;
                                    } else {
                                        env = env.withVars(set.vars());
                                    }
                                }
                                case HookOutput.Fail fail -> failed = fail.reason();
                                // Only the hook of a choose may pick
                                case HookOutput.Pick pick -> failed = HookOutput.HOOK_ERROR;
                            }
                            trace.add(TraceEvent.of("hook", hook.id(), failed == null ? "ok" : failed, at));
                        }
                        case Succeed succeed -> {
                            return end(
                                    state, env, effects, revision, succeeds(entry.kind(), env), at, currentStep(stack));
                        }
                        case Fail fail -> {
                            return end(state, env, effects, revision, fail.reason(), at, currentStep(stack));
                        }
                    }
                }
            }
            if (failed != null) {
                // Unwind to the innermost retry with attempts left and rerun its body; without one,
                // the entry fails, at the node that failed.
                @Nullable String step = currentStep(stack);
                Frame.@Nullable RetryExit again = null;
                while (again == null && !stack.isEmpty()) {
                    if (stack.removeLast() instanceof Frame.RetryExit r
                            && r.attempt() < ((Retry) resolve(root, r.path())).attempts()) {
                        again = r;
                    }
                }
                if (again == null) return end(state, env, effects, revision, failed, at, step);
                trace.add(TraceEvent.of("retry", null, failed, site(state, again.path())));
                stack.add(new Frame.RetryExit(again.path(), again.attempt() + 1));
                stack.add(new Frame.Eval(child(again.path(), 0)));
                error = failed;
                attemptsLeft = ((Retry) resolve(root, again.path())).attempts() - again.attempt();
            }
        }
        return end(state, env, effects, revision, "incomplete", site(state, List.of()), null);
    }

    private static Site site(FlowState state, List<Integer> path) {
        return new Site(state.entry(), path);
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
                trace.add(TraceEvent.of("commit", handler.name(), failure, e.at()));
                return e.withFailure(failure);
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
     * @param budget what the run spent on hooks so far; spent, the hook is not run at all
     */
    private HookOutput runHook(FlowState state, Hook hook, Env env, @Nullable List<String> options, HookBudget budget) {
        if (budget.runs >= FlowLimits.RUN_HOOK_RUNS || budget.millis >= FlowLimits.RUN_HOOK_MILLIS) {
            return new HookOutput.Fail(HookOutput.HOOK_ERROR);
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("request", env.request());
        ctx.put("client", env.client());
        ctx.put("host", env.host());
        ctx.put("tenant", env.tenant());
        ctx.put("user", env.user());
        ctx.put("vars", env.vars());
        if (options != null) ctx.put("options", options);
        long began = clock.millis();
        try {
            budget.runs++;
            return HookOutput.parse(hooks.run(state.tenantId(), hook, ctx))
                    .orElse(new HookOutput.Fail(HookOutput.HOOK_ERROR));
        } catch (HookException e) {
            return new HookOutput.Fail(HookOutput.HOOK_ERROR);
        } finally {
            budget.millis += clock.millis() - began;
        }
    }

    /** What one run spent on hooks: calls made and wall time the runner took, queueing included. */
    private static final class HookBudget {
        int runs;
        long millis;
    }

    /**
     * Why an entry that reached {@code succeed} still fails, or null. An authenticate entry must have
     * verified a credential for its subject, reached the assurance its tenant requires, and reached
     * the assurance the client asked for in {@code acr_values}.
     */
    private static @Nullable String succeeds(Entry.Kind kind, Env env) {
        if (kind == Entry.Kind.CONSENT) return null;
        // What counts is an amr proved, not a verification recorded: one with none proves nothing
        if (env.auth().subject() == null || env.auth().acr() == null) return "unauthenticated";
        if (kind == Entry.Kind.APPROVE) {
            return Acr.satisfies(env.auth().acr(), required(env.tenant())) ? null : "mfa_required";
        }
        if (!Acr.satisfies(env.auth().acr(), required(env.tenant()))) return "mfa_required";
        // toEnv() always writes a list; anything else is a corrupt state, which asks for the most
        Object requested = env.host().get(HostParams.ACR_VALUES);
        if (requested != null && !(requested instanceof List<?>)) return "acr_not_satisfied";
        return Acr.satisfies(env.auth().acr(), requested) ? null : "acr_not_satisfied";
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
        return NodePaths.resolve(root, path);
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

    private static Run suspend(
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
        FlowState suspended = state(state, stack, env, awaiting, effects, revision);
        // A flow may not suspend into a state too big to keep; it ends, never retried
        if (FlowStateCodec.write(suspended).getBytes(StandardCharsets.UTF_8).length > FlowLimits.MAX_STATE_BYTES) {
            return end(
                    state, env, effects, revision, LIMIT_EXCEEDED, new Site(state.entry(), path), currentStep(stack));
        }
        return new Suspended(suspended);
    }

    /** The entry ended; its staged writes go with a success and are dropped with a failure. */
    private static Ended end(
            FlowState state,
            Env env,
            List<Effect> effects,
            long revision,
            @Nullable String failure,
            Site at,
            @Nullable String step) {
        return new Ended(
                state(state, List.of(), env, null, List.of(), revision),
                failure,
                failure == null ? effects : List.of(),
                at,
                step);
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
                revision,
                s.preview(),
                s.proven(),
                s.remember(),
                s.expiry());
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
        // Past a limit of its tenant's flow settings: refused, nothing changes, and the host's
        // challenge expires on its own (ADR 0035)
        if (state.expiry() != null && state.expiry().passed(clock.millis())) {
            throw new FlowException(Code.FLOW_EXPIRED);
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
        if (!picker && screens.find(awaiting.screen()).isEmpty()) return "screen " + awaiting.screen();
        // A screen that acts for the signed in subject cannot wait without one, whatever its issuer.
        // Whether a credential verified it first is the checker's rule at publish: the engine itself
        // writes such a state for a step up, whose subject the host bound with nothing verified yet
        if (waiting instanceof Screen s
                && state.env().auth().subject() == null
                && screens.find(s.type())
                        .map(spec -> spec.requiresSubject(s.opts()))
                        .orElse(false)) {
            return "screen " + awaiting.screen() + " without a subject";
        }
        if (screens.issuer(awaiting.screen())
                .map(i -> !i.resumable(state.env(), awaiting.data()))
                .orElse(false)) {
            return "issued material of " + awaiting.screen();
        }
        if (ScreenRegistry.METHOD_PICKER.equals(awaiting.screen())
                != state.stack().stream()
                        .anyMatch(f -> f instanceof Frame.Pick && f.path().equals(awaiting.path()))) {
            return "picker frame";
        }
        return null;
    }

    private static @Nullable Node resolveOrNull(Node root, List<Integer> path) {
        return NodePaths.find(root, path).orElse(null);
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

    /**
     * The data a new instance of a screen waits with: the spec's own, plus what the screen's issuer
     * issues; null when the issuer failed, which fails the step closed.
     */
    private @Nullable Map<String, Object> prepare(
            ScreenSpec spec,
            Env env,
            CredentialIssuer.Context context,
            Map<String, Object> opts,
            Map<String, Object> previous) {
        Map<String, Object> data = new LinkedHashMap<>(spec.prepare(env, opts, previous));
        Optional<CredentialIssuer> issuer = screens.issuer(spec.type());
        if (issuer.isEmpty()) return data;
        Map<String, Object> issued;
        try {
            issued = issuer.get().issue(env, context, opts, previous);
        } catch (RuntimeException e) {
            return null;
        }
        merge(data, issued, "issuer of " + spec.type());
        return data;
    }

    /** Adds {@code more} to {@code into}; a key both hold is a bug in the spec or its issuer. */
    private static void merge(Map<String, Object> into, Map<String, Object> more, String who) {
        more.forEach((k, v) -> {
            if (into.putIfAbsent(k, v) != null) throw new IllegalStateException(who + " wrote '" + k + "' twice");
        });
    }

    private ScreenSpec screen(String type) {
        return screens.find(type).orElseThrow(() -> new IllegalStateException("unknown screen " + type));
    }

    /** The screen the flow waits on, at the path its node sets (ADR 0029); a picker has its default. */
    private ScreenView view(FlowState state, FlowVersion version) {
        var awaiting = state.awaiting();
        if (awaiting == null) throw new FlowException(Code.FLOW_FINISHED);
        ScreenSpec spec = screen(awaiting.screen());
        Map<String, Object> screen = new LinkedHashMap<>(spec.render(state.env(), awaiting.data()));
        Optional<CredentialIssuer> issuer = screens.issuer(awaiting.screen());
        if (issuer.isPresent()) {
            Map<String, Object> shown;
            try {
                shown = issuer.get().present(state.env(), awaiting.data());
            } catch (RuntimeException e) {
                // The material the flow waits with cannot be shown: this view is refused as an unknown
                // flow, and the flow stays unusable until it expires
                LOG.log(
                        System.Logger.Level.WARNING,
                        "issuer of {0} cannot present; the flow is refused until it expires",
                        awaiting.screen());
                throw new FlowException(Code.FLOW_NOT_FOUND);
            }
            merge(screen, shown, "issuer of " + awaiting.screen());
        }
        if (awaiting.error() != null) screen.put("errors", List.of(Map.of("code", awaiting.error())));
        if (awaiting.attemptsLeft() != null) screen.put("attemptsLeft", awaiting.attemptsLeft());
        Entry entry = Objects.requireNonNull(version.flow().entries().get(state.entry()), "the state fits its version");
        Map<String, Object> opts =
                resolve(entry.body(), awaiting.path()) instanceof Screen node ? node.opts() : Map.of();
        String path = ScreenPaths.of(spec, opts, awaiting.step());
        return new ScreenView(state.flowId(), awaiting.nonce(), awaiting.step(), path, screen);
    }

    private Completion finish(HostRequest req, Ended ended) {
        FlowState s = ended.state();
        String failure = ended.failure();
        if (failure != null) {
            report(s, failureSite(ended, failure));
            Completion rejected = reported(s, ended, () -> host.reject(req, "access_denied", failure));
            emit(s, List.of(TraceEvent.of("end", null, failure, ended.at())));
            // A failed sign in, not a denied consent: a consent the person refused goes back to the app
            return s.kind() == HostKind.LOGIN && rejected instanceof Redirect r
                    ? new Redirect(r.url(), true)
                    : rejected;
        }
        Env.Auth auth = s.env().auth();
        Completion accepted = reported(s, ended, () -> switch (s.kind()) {
            // succeeds() refused any login without a subject and a verified credential
            case LOGIN ->
                host.accept(
                        req,
                        new Authenticated(
                                Objects.requireNonNull(auth.subject()),
                                auth.amr(),
                                Objects.requireNonNull(auth.acr()),
                                Map.of("flowId", s.flowId(), "versionHash", s.versionHash()),
                                s.remember()));
            // Only what the login that proved this subject learned, as the consent started: user.*
            // filled inside the consent entry may describe someone else (O16), and the login row
            // may have expired since
            case CONSENT -> {
                String subject = Objects.requireNonNull(auth.subject());
                // A consent saved before FlowState.proven existed has none; its login, if still
                // stored and proven for this subject, stands in (only for one flow TTL after deploy)
                Map<String, Object> proven = s.proven().isEmpty()
                        ? linkedLogin(req, subject).map(l -> l.env().user()).orElse(Map.of())
                        : s.proven();
                yield host.accept(req, consented(req, proven, subject));
            }
            // The approval was written by the entry's last handler; the host tells the phone it is done
            case APPROVE ->
                host.accept(
                        req,
                        new Authenticated(
                                Objects.requireNonNull(auth.subject()),
                                auth.amr(),
                                Objects.requireNonNull(auth.acr()),
                                Map.of("flowId", s.flowId(), "versionHash", s.versionHash())));
            case LOGOUT -> throw new IllegalStateException("logout runs no flow entry");
        });
        emit(
                s,
                List.of(
                        s.kind() == HostKind.LOGIN || s.kind() == HostKind.APPROVE
                                ? new TraceEvent("end", null, "succeed", auth.amr(), auth.acr(), ended.at())
                                : TraceEvent.of("end", null, "succeed", ended.at())));
        return accepted;
    }

    /**
     * Calls the host. If it throws, the failure is reported as {@code host_error} and a preview's
     * trace ends with it, then a {@link HostFailure} carrying the site propagates.
     */
    private Completion reported(FlowState state, Ended ended, Supplier<Completion> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            FailureSite site = failureSite(ended, HOST_ERROR);
            report(state, site);
            emit(state, List.of(TraceEvent.of("end", null, HOST_ERROR, ended.at())));
            throw new HostFailure(site, e);
        }
    }

    /** Tells {@link Failures}; one that throws must not keep the host from being told, so it is only logged. */
    private void report(FlowState state, FailureSite site) {
        try {
            failures.failed(state, site);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "failure reporter threw for {0}", site.reference());
        }
    }

    private static FailureSite failureSite(Ended ended, String code) {
        return new FailureSite(ended.at().entry(), ended.at().path(), ended.step(), code, reference());
    }

    /** {@code ref_} and 12 random letters and digits: unique enough per failure to find one log line. */
    private static String reference() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder out = new StringBuilder("ref_");
        for (int i = 0; i < 12; i++) out.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return out.toString();
    }

    /** Hands a preview flow's events to {@link Previews}; other flows are not traced. */
    private void emit(FlowState state, List<TraceEvent> events) {
        if (!state.preview()) return;
        events.forEach(e -> previews.trace(state, e));
    }

    /** The finished login whose flow id the host hands back in the consent request's context, if this subject's. */
    private Optional<FlowState> linkedLogin(HostRequest req, String subject) {
        if (!(req.params().context().get("flowId") instanceof String flowId)) return Optional.empty();
        return store.load(flowId)
                // Same tenant and same preview flag as this request: the host decides both, and a
                // relayed context must not carry a consent into another tenant's or a draft's login
                .filter(s -> s.kind() == HostKind.LOGIN
                        && s.awaiting() == null
                        && s.tenantId().equals(req.tenantOrDefault())
                        && s.preview() == req.preview()
                        && s.env().auth().subject() != null
                        && s.env().auth().subject().equals(subject));
    }

    /**
     * Grants every requested scope and audience, with what the login that proved {@code subject}
     * learned about the person (empty when the host links no such login); the host decides which of
     * it becomes claims.
     */
    private static Consented consented(HostRequest req, Map<String, Object> user, String subject) {
        return new Consented(
                subject, req.params().requestedScope(), req.params().requestedAccessTokenAudience(), user);
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
