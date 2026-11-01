package faye.check;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import faye.engine.Acr;
import faye.engine.Effect;
import faye.engine.Engine;
import faye.engine.Env;
import faye.engine.FlowException;
import faye.engine.FlowState;
import faye.engine.FlowStateCodec;
import faye.engine.FlowVersion;
import faye.engine.Handler;
import faye.engine.HandlerMeta;
import faye.engine.HandlerRegistry;
import faye.engine.HookRunner;
import faye.engine.InMemoryFlowStore;
import faye.engine.InMemoryFlowVersions;
import faye.engine.Invocation;
import faye.engine.Outcome;
import faye.engine.Previews;
import faye.engine.Submission;
import faye.engine.TenantPolicies;
import faye.engine.TenantPolicy;
import faye.engine.spi.Authenticated;
import faye.engine.spi.EngineResponse;
import faye.engine.spi.FlowStore;
import faye.engine.spi.HostKind;
import faye.engine.spi.HostParams;
import faye.engine.spi.Redirect;
import faye.engine.spi.ScreenView;
import faye.engine.testing.FixedIssuers;
import faye.engine.testing.MockFlowHost;
import faye.hooks.HookException;
import faye.ir.Flow;
import faye.ir.HookDef;
import faye.ir.IrCodec;
import faye.ir.IrException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * F6 and F13 (ADR 0007): the engine as a state machine under hostile input, against the charter's
 * guarantees as oracles.
 *
 * <p>Each input picks a flow (a conformance flow, or one generated from the IR grammar), keeps it
 * only if the checker accepts it (only checked flows are ever published), and then drives one
 * login through a sequence of actions the fuzzer chooses: valid and random answers, stale nonces,
 * foreign binders, a state reloaded through {@link FlowStateCodec}, an engine restarted on the same
 * store, a submit that loses the revision race, a newer version published mid flow, a second flow
 * for the same challenge, and a consent after the login. Handlers and hooks answer with outcomes
 * the fuzzer picks, including subjects that differ between credentials, amr the handler does not
 * declare, and hook output that tries to write {@code auth}.
 *
 * <p>After every action it checks: only {@link FlowException} escapes (O1); a refused submit leaves
 * the stored state and every account unchanged (O6); every accept has a subject that a credential
 * proved in this flow, or comes from a remembered session as one factor (O3); {@code amr} is what
 * handlers proved and declared, {@code acr} is computed from it and meets the client and the tenant
 * (O4); a bound subject never changes (O5); nothing a browser posts reaches {@code user}, {@code
 * vars} or {@code auth} (O8); stored states round trip (O9); a flow ends at most once (O7); every
 * screen matches the screen protocol (O14); a flow the checker accepted never reaches {@code
 * succeed} without a credential (O12, the checker's soundness); and the host is asked to remember a
 * sign in only when the browser asked for it on a screen that offered it (ADR 0033).
 */
class EngineFuzzTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    static final String TENANT = Engine.DEFAULT_TENANT;
    static final List<String> SUBJECTS = List.of("alice", "bob");
    /** Keys a browser's payload carries here; none may reach the environment. */
    static final String POSTED = "p_";

    static final HookDef HOOK = new HookDef("export default () => ({})", List.of(), HookDef.DEFAULT_TIMEOUT_MS);
    static final List<Flow> CONFORMANCE = conformanceFlows();
    static final Map<String, JsonNode> META = Fixtures.metadata();
    static final JsonSchema SCREEN = JsonSchemaFactory.getInstance(
                    SpecVersion.VersionFlag.V202012,
                    b -> b.schemaMappers(m -> m.mapPrefix(
                            "https://faye.invalid/schema/",
                            Fixtures.ROOT.resolve("src/schema").toUri().toString())))
            .getSchema(SchemaLocation.of("https://faye.invalid/schema/screen.schema.json#/$defs/ScreenResponse"));

    /** Inputs every build runs on top of the stored ones, from a fixed seed so a failure reproduces. */
    static final int SWEEP = Integer.getInteger("faye.fuzz.sweep", 3000);

    /**
     * Regression mode replays only the stored inputs; this runs {@link #SWEEP} more, so the build
     * itself exercises the state machine broadly. A failing input's index and seed are in the message.
     */
    @Test
    void sweep() {
        java.util.Random random = new java.util.Random(0x5eed);
        for (int i = 0; i < SWEEP; i++) {
            byte[] input = new byte[random.nextInt(64, 2048)];
            random.nextBytes(input);
            try {
                run(new Source.Bytes(input));
            } catch (AssertionError | RuntimeException e) {
                throw new AssertionError(
                        "sweep input " + i + ": " + java.util.HexFormat.of().formatHex(input), e);
            }
        }
        // The generator must keep reaching the engine: at 3000 inputs it measured 689 checked and
        // 1870 unchecked flows, 740 accepted, 2997 rejected (813 of them by the engine's own
        // credential check), 281 consents (ADR 0007). Floors well below that catch a generator or
        // checker change that quietly stops the harness testing anything.
        String reached = "reached " + new java.util.TreeMap<>(REACHED);
        assertThat(REACHED.getOrDefault("checked", 0)).as(reached).isGreaterThan(SWEEP / 15);
        assertThat(REACHED.getOrDefault("accepted", 0)).as(reached).isGreaterThan(SWEEP / 30);
        assertThat(REACHED.getOrDefault("rejected", 0)).as(reached).isGreaterThan(SWEEP / 15);
        assertThat(REACHED.getOrDefault("consent ended", 0)).as(reached).isGreaterThan(SWEEP / 100);
        assertThat(REACHED.getOrDefault("consent for another subject", 0))
                .as(reached)
                .isPositive();
        assertThat(REACHED.getOrDefault("engine refused unauthenticated", 0))
                .as(reached)
                .isGreaterThan(SWEEP / 15);
    }

    @FuzzTest(maxDuration = "120s")
    void loginStateMachine(FuzzedDataProvider data) {
        run(new Source.Fuzzed(data));
    }

    /** What the inputs reached, so a generator that stops reaching the engine fails the sweep. */
    static final Map<String, Integer> REACHED = new java.util.concurrent.ConcurrentHashMap<>();

    static void reached(String what) {
        REACHED.merge(what, 1, Integer::sum);
    }

    static void run(Source data) {
        Flow flow = flow(data);
        if (flow == null) return;
        reached("flow");
        boolean mfaRequired = data.consumeBoolean();
        // Every identifier screen offers "keep me signed in" (ADR 0033), so answers may carry it
        TenantPolicy policy = new TenantPolicy(mfaRequired, null, null, Set.of(), null, true);
        Report report = Checker.check(flow, Fixtures.handlers(), Fixtures.screens(), policy, hash -> Optional.of(HOOK));
        if (report.ok()) {
            reached("checked");
            new Run(data, flow, policy, true).drive();
            return;
        }
        // A flow the checker refuses is never published. Running the ones the engine's own catalog
        // check (L1) lets through tests the engine's guards on their own, as if the checker had a
        // bug: they must still never accept without proof (O3 to O5). Only these may end in an
        // IllegalStateException, the engine's refusal of a shape the checker rules out.
        if (!faye.engine.Verifier.verify(flow, Fixtures.handlers(), Fixtures.screens())
                .isEmpty()) {
            return;
        }
        reached("unchecked");
        try {
            new Run(data, flow, policy, false).drive();
        } catch (IllegalStateException unreachable) {
            reached("unchecked illegal state");
        }
    }

    // ---- one run ----

    /** Everything one input drives, and what the oracles compare against. */
    static final class Run {
        final Source data;
        final InMemoryFlowVersions versions = new InMemoryFlowVersions();
        final Store store = new Store();
        final MockFlowHost host = new MockFlowHost();
        final List<Fake> fakes = new ArrayList<>();
        final List<String> commits = new ArrayList<>();
        /** Subject to the amr values credentials proved for it, as declared by their handlers. */
        final Map<String, Set<String>> proved = new LinkedHashMap<>();
        /** Some answer this browser posted asked to stay signed in. */
        boolean askedToRemember;

        final TenantPolicy policy;
        final Map<String, Object> params = new LinkedHashMap<>();
        Engine engine;
        /** Flows started for the challenge, and whether each has ended. */
        final Map<String, Boolean> flows = new LinkedHashMap<>();

        /** Whether the checker accepted the flow, so the checker's claims hold too (O12). */
        final boolean checked;

        Run(Source data, Flow flow, TenantPolicy policy, boolean checked) {
            this.data = data;
            this.checked = checked;
            this.policy = policy;
            versions.publish(TENANT, FlowVersion.of(flow));
            META.forEach((name, m) -> fakes.add(new Fake(name, HandlerMeta.fromYaml(m), this)));
            engine = engine();
            // The authorization request, as the host relays it
            if (data.consumeBoolean()) params.put("subject", pick(SUBJECTS));
            if (data.consumeBoolean()) params.put("skip", true);
            switch (data.consumeInt(0, 4)) {
                case 0 -> params.put("acr_values", List.of(Acr.TWO_FACTOR));
                case 1 -> params.put("acr_values", List.of(Acr.ONE_FACTOR, "unknown"));
                case 2 -> params.put("acr_values", Acr.TWO_FACTOR);
                case 3 -> params.put("acr_values", List.of());
                default -> {}
            }
            params.put("requested_scope", List.of("openid", "email"));
            Map<String, Object> client = new LinkedHashMap<>();
            client.put("client_id", "demo");
            client.put("first_party", data.consumeBoolean());
            host.register(MockFlowHost.request(HostKind.LOGIN, "c1", client, HostParams.fromMap(params)));
        }

        Engine engine() {
            return Engine.builder()
                    .versions(versions)
                    .handlers(new HandlerRegistry(fakes))
                    .store(store)
                    .host(host)
                    .tenants(TenantPolicies.of(Map.of(TENANT, policy)))
                    .hooks(new FuzzedHooks(this))
                    .previews(Previews.NONE)
                    .screens(Fixtures.screens())
                    .build();
        }

        void drive() {
            EngineResponse last = start();
            int actions = data.consumeInt(1, 40);
            for (int i = 0; i < actions && data.remainingBytes() > 0; i++) {
                if (last instanceof ScreenView screen) {
                    last = act(screen);
                } else {
                    // Finished: a finished flow takes no more answers, and the host hears nothing more
                    if (!flows.containsValue(false) && data.consumeBoolean()) consent();
                    last = data.consumeBoolean() ? start() : last;
                    if (last instanceof Redirect) return;
                }
            }
        }

        EngineResponse start() {
            int before = outcomes();
            // Now and then the phone's side of a QR code sign in, which a flow without an approve entry refuses
            HostKind kind =
                    !params.containsKey("skip") && data.consumeInt(0, 3) == 0 ? HostKind.APPROVE : HostKind.LOGIN;
            if (kind == HostKind.APPROVE) reached("approve");
            EngineResponse r = engine.start(kind, "c1", Map.of("ip", "198.51.100.7"), "binder");
            if (r instanceof ScreenView s) {
                flows.put(s.flowId(), false);
                screen(s);
            } else {
                assertThat(outcomes())
                        .as("a flow that ends at once tells the host once")
                        .isEqualTo(before + 1);
                checkOutcomes();
            }
            return r;
        }

        /** One action on a flow that waits on a screen. */
        EngineResponse act(ScreenView screen) {
            String flowId = screen.flowId();
            String before = store.snapshot(flowId);
            int commitsBefore = commits.size();
            int outcomesBefore = outcomes();
            int action = data.consumeInt(0, 10);
            try {
                EngineResponse r = switch (action) {
                    case 0, 1, 2 -> submit(screen, screen.nonce(), "binder", valid(screen));
                    case 3 -> submit(screen, screen.nonce(), "binder", posted());
                    case 4 -> submit(screen, "n_" + data.consumeAsciiString(8), "binder", valid(screen));
                    case 5 -> submit(screen, screen.nonce(), "other-" + data.consumeAsciiString(8), valid(screen));
                    case 6 -> {
                        // Reload: the stored state through its codec, as after a restart
                        store.roundTrip(flowId);
                        yield engine.view(flowId, "binder");
                    }
                    case 7 -> {
                        engine = engine();
                        yield engine.view(flowId, "binder");
                    }
                    case 8 -> {
                        store.loseNext = true;
                        yield submit(screen, screen.nonce(), "binder", valid(screen));
                    }
                    case 9 -> {
                        // A screen action (ADR 0032): declared or not, with input or not
                        String name = pick(List.of("resend", "send", "poll", ""));
                        Map<String, Object> input = new LinkedHashMap<>();
                        if (data.consumeBoolean()) {
                            input.put("phone", data.consumeBoolean() ? "+15551234821" : data.consumeAsciiString(16));
                        }
                        EngineResponse acted = engine.act(flowId, "binder", screen.nonce(), name, input);
                        assertThat(acted).as("an action stays on its screen").isInstanceOf(ScreenView.class);
                        assertThat(((ScreenView) acted).screen().get("type"))
                                .isEqualTo(screen.screen().get("type"));
                        yield acted;
                    }
                    default -> {
                        // A newer version does not move a running flow
                        versions.publish(TENANT, FlowVersion.of(pickFlow()));
                        yield engine.view(flowId, "binder");
                    }
                };
                reached("action " + action);
                // A stale nonce, another browser's binder and a lost race are always refused
                assertThat(action).as("must have been refused").isNotIn(4, 5, 8);
                if (r instanceof ScreenView next) {
                    screen(next);
                    assertThat(commits.size())
                            .as("writes apply only when an entry succeeds")
                            .isEqualTo(commitsBefore);
                    assertThat(outcomes())
                            .as("a waiting flow has not told the host")
                            .isEqualTo(outcomesBefore);
                } else {
                    assertThat(flows.put(flowId, true)).as("a flow ends once").isFalse();
                    assertThat(outcomes())
                            .as("an ended flow tells the host once")
                            .isEqualTo(outcomesBefore + 1);
                    checkOutcomes();
                    if (commits.size() > commitsBefore) {
                        assertThat(host.accepted())
                                .as("writes apply only when the sign in is accepted")
                                .isNotEmpty();
                    }
                    assertThat(store.inner.load(flowId))
                            .as("a finished flow is stored as finished")
                            .hasValueSatisfying(s -> assertThat(s.awaiting()).isNull());
                    for (int i = 0; i < 2; i++) {
                        try {
                            engine.submit(flowId, "binder", screen.nonce(), new Submission(valid(screen)));
                            throw new AssertionError("a finished flow took an answer");
                        } catch (FlowException expected) {
                            assertThat(outcomes()).isEqualTo(outcomesBefore + 1);
                        }
                    }
                }
                return r;
            } catch (FlowException refused) {
                reached("refused " + refused.code().wireName());
                // A live flow is always found by its own browser, whatever was done to it before
                // (F7's consistency check must never refuse a state the engine itself wrote); only
                // another browser's binder (action 5) finds nothing
                if (action != 5) {
                    assertThat(refused.code())
                            .as("a live flow is found")
                            .isNotIn(FlowException.Code.FLOW_NOT_FOUND, FlowException.Code.FLOW_FINISHED);
                }
                // O6: a refusal changes nothing that lasts
                assertThat(store.snapshot(flowId))
                        .as("refused: state unchanged")
                        .isEqualTo(before);
                assertThat(commits.size()).as("refused: no account changed").isEqualTo(commitsBefore);
                assertThat(outcomes()).as("refused: the host heard nothing").isEqualTo(outcomesBefore);
                store.loseNext = false;
                return screen;
            }
        }

        EngineResponse submit(ScreenView screen, String nonce, String binder, Map<String, Object> payload) {
            return engine.submit(screen.flowId(), binder, nonce, new Submission(payload));
        }

        /** O14, and the state behind a screen: O5, O8, O9. */
        void screen(ScreenView view) {
            var errors = SCREEN.validate(JSON.valueToTree(view));
            assertThat(errors).as("screen protocol: " + view).isEmpty();
            FlowState state = store.inner.load(view.flowId()).orElseThrow();
            assertThat(state.awaiting()).isNotNull();
            Env env = state.env();
            // O8: what a browser posts is never the environment
            for (String key : env.user().keySet()) assertThat(key).doesNotStartWith(POSTED);
            for (String key : env.vars().keySet()) assertThat(key).doesNotStartWith(POSTED);
            // O3 and O4 inside the flow: only declared amr of credentials, each proved for this subject
            for (Env.Verified v : env.auth().verified()) {
                HandlerMeta meta =
                        HandlerMeta.fromYaml(java.util.Objects.requireNonNull(META.get(v.handler()), v.handler()));
                assertThat(meta.amr()).as("verified by a credential").isNotEmpty();
                assertThat(meta.amr()).containsAll(v.amr());
            }
            // O5: a bound subject stays bound
            String subject = env.auth().subject();
            String was = store.subjects.get(view.flowId());
            if (was != null) assertThat(subject).as("the subject is sticky").isEqualTo(was);
            if (subject != null) store.subjects.put(view.flowId(), subject);
            // O9
            String written = FlowStateCodec.write(state);
            FlowState read = FlowStateCodec.read(written).orElseThrow();
            assertThat(read).as("state round trip").isEqualTo(state);
            assertThat(FlowStateCodec.write(read)).isEqualTo(written);
        }

        /** O3, O4 and O12 on everything the host was told. */
        void checkOutcomes() {
            if (!host.accepted().isEmpty()) reached("accepted");
            if (!host.rejected().isEmpty()) reached("rejected");
            for (MockFlowHost.Accepted a : host.accepted()) {
                Authenticated outcome = a.outcome();
                assertThat(outcome.subject()).isNotEmpty();
                assertThat(new LinkedHashSet<>(outcome.amr()))
                        .as("amr has no repeats")
                        .hasSameSizeAs(outcome.amr());
                // ADR 0033: only the person asks the host to remember a sign in
                if (outcome.remember()) {
                    assertThat(askedToRemember)
                            .as("remember only when the browser asked")
                            .isTrue();
                }
                if (outcome.amr().isEmpty()) {
                    // A remembered session, counted as one factor; no screen ran to ask
                    assertThat(outcome.remember())
                            .as("a remembered session asks nothing")
                            .isFalse();
                    assertThat(params.get("skip")).isEqualTo(true);
                    assertThat(outcome.subject()).isEqualTo(params.get("subject"));
                    assertThat(outcome.acr()).isEqualTo(Acr.ONE_FACTOR);
                } else {
                    assertThat(proved.getOrDefault(outcome.subject(), Set.of()))
                            .as("amr is what credentials proved for " + outcome.subject())
                            .containsAll(outcome.amr());
                    assertThat(outcome.acr()).isEqualTo(Acr.of(outcome.amr()));
                    // Step up (a remembered session that is not enough) binds the host's subject; a
                    // named subject without skip is a forced re-authentication and binds nothing
                    if (Boolean.TRUE.equals(params.get("skip")) && params.get("subject") instanceof String bound) {
                        assertThat(outcome.subject())
                                .as("step up keeps the host's subject")
                                .isEqualTo(bound);
                    }
                }
                assertThat(Acr.satisfies(outcome.acr(), params.get("acr_values")))
                        .as("meets acr_values " + params.get("acr_values"))
                        .isTrue();
                if (policy.mfaRequired()) assertThat(outcome.acr()).isEqualTo(Acr.TWO_FACTOR);
            }
            // O12: the checker proved every success path verifies a credential, so the engine's own
            // check at succeed never has to refuse one
            for (MockFlowHost.Rejected r : host.rejected()) {
                if (checked) {
                    assertThat(r.description()).as("checker soundness").isNotEqualTo("unauthenticated");
                } else if ("unauthenticated".equals(r.description())) {
                    reached("engine refused unauthenticated");
                }
            }
        }

        /** A consent after the login, for the login's subject or another one. */
        void consent() {
            if (host.accepted().isEmpty()) return;
            Authenticated login = host.accepted().get(0).outcome();
            String subject = data.consumeBoolean() ? login.subject() : pick(SUBJECTS);
            Map<String, Object> consent = new LinkedHashMap<>();
            consent.put("subject", subject);
            consent.put("context", login.context());
            consent.put("requested_scope", List.of("openid", "email"));
            host.register(MockFlowHost.request(
                    HostKind.CONSENT, "k1", Map.of("client_id", "demo"), HostParams.fromMap(consent)));
            int before = host.consented().size() + host.rejected().size();
            int consentedBefore = host.consented().size();
            EngineResponse r = engine.start(HostKind.CONSENT, "k1", Map.of(), "binder2");
            for (int i = 0; i < 4 && r instanceof ScreenView s; i++) {
                screen(s);
                try {
                    r = engine.submit(s.flowId(), "binder2", s.nonce(), new Submission(valid(s)));
                } catch (FlowException e) {
                    return;
                }
            }
            if (r instanceof Redirect) {
                reached("consent ended");
                assertThat(host.consented().size() + host.rejected().size()).isEqualTo(before + 1);
                host.consented()
                        .forEach(c -> assertThat(c.outcome().subject())
                                .as("consent is for the subject the host asked about")
                                .isEqualTo(c.request().params().subject()));
                // O16: a consent for someone other than the login's subject never carries the
                // login's claims, whatever context the host relays
                // only this consent's outcome: host.consented() holds every earlier one of the run
                if (!subject.equals(login.subject()) && host.consented().size() > consentedBefore) {
                    assertThat(host.consented().getLast().outcome().user())
                            .as("another subject's attributes")
                            .doesNotContainKey("email");
                    reached("consent for another subject");
                }
            }
        }

        int outcomes() {
            return host.accepted().size() + host.rejected().size();
        }

        /** An answer the screen accepts. */
        Map<String, Object> valid(ScreenView view) {
            Object type = view.screen().get("type");
            Map<String, Object> out = new LinkedHashMap<>();
            switch (String.valueOf(type)) {
                case "identifier" -> {
                    if (view.screen().containsKey("passkey") && data.consumeBoolean()) {
                        out.put("passkey", Map.of("id", "k"));
                    } else {
                        out.put("identifier", pick(List.of("alice@example.com", "bob@example.com", " x")));
                        if (view.screen().containsKey("password")) {
                            out.put("password", data.consumeBoolean() ? "right" : "wrong");
                        }
                    }
                    // Offered on every identifier screen here: absent, ticked, unticked
                    if (view.screen().containsKey("remember")) {
                        switch (data.consumeInt(0, 2)) {
                            case 1 -> {
                                out.put(Engine.REMEMBER, true);
                                askedToRemember = true;
                            }
                            case 2 -> out.put(Engine.REMEMBER, false);
                            default -> {}
                        }
                    }
                }
                case "password" -> out.put("password", data.consumeBoolean() ? "right" : "wrong");
                case "passkey" -> {
                    if (view.screen().containsKey("register") && data.consumeBoolean()) out.put("skip", true);
                    else out.put("passkey", Map.of("id", "k"));
                }
                case "otp", "otp_enroll" -> out.put("code", "123456");
                case "qr_approve" -> out.put("number", data.consumeBoolean() ? FixedIssuers.QR_NUMBER : 17L);
                case "consent" -> out.put("decision", data.consumeBoolean() ? "allow" : "deny");
                case "method_picker" -> {
                    if (view.screen().get("options") instanceof List<?> options && !options.isEmpty()) {
                        Object o = options.get(data.consumeInt(0, options.size() - 1));
                        out.put("method", o instanceof Map<?, ?> m ? m.get("id") : o);
                    }
                }
                default -> {}
            }
            return out;
        }

        /** Whatever a browser might post: any shape, with nulls, wrong types and server field names. */
        @SuppressWarnings("unchecked")
        Map<String, Object> posted() {
            Map<String, @Nullable Object> out = new LinkedHashMap<>();
            int n = data.consumeInt(0, 4);
            for (int i = 0; i < n; i++) {
                String key = switch (data.consumeInt(0, 6)) {
                    case 0 -> "identifier";
                    case 1 -> "password";
                    case 2 -> "code";
                    case 3 -> "passkey";
                    case 4 -> "decision";
                    case 5 -> "method";
                    default -> POSTED + data.consumeAsciiString(6);
                };
                out.put(key, value(2));
            }
            // JSON as Jackson reads it, nulls included
            return (Map<String, Object>) (Map<String, ?>) out;
        }

        @Nullable
        Object value(int depth) {
            return switch (data.consumeInt(0, depth > 0 ? 8 : 5)) {
                case 0 -> null;
                case 1 -> data.consumeBoolean();
                case 2 -> data.consumeLong();
                case 3 -> data.consumeRegularDouble();
                case 4 -> data.consumeString(40);
                case 5 -> "123456";
                case 6 -> {
                    List<@Nullable Object> list = new ArrayList<>();
                    int n = data.consumeInt(0, 3);
                    for (int i = 0; i < n; i++) list.add(value(depth - 1));
                    yield list;
                }
                default -> {
                    Map<String, @Nullable Object> map = new LinkedHashMap<>();
                    int n = data.consumeInt(0, 3);
                    for (int i = 0; i < n; i++) {
                        map.put(pick(List.of("id", "subject", "amr", POSTED + i)), value(depth - 1));
                    }
                    yield map;
                }
            };
        }

        <T> T pick(List<T> list) {
            return list.get(data.consumeInt(0, list.size() - 1));
        }

        Flow pickFlow() {
            return CONFORMANCE.get(data.consumeInt(0, CONFORMANCE.size() - 1));
        }
    }

    /** Answers with what the fuzzer picks, and records what credentials proved. */
    static final class Fake implements Handler {
        final String name;
        final HandlerMeta meta;
        final Run run;

        Fake(String name, HandlerMeta meta, Run run) {
            this.name = name;
            this.meta = meta;
            this.run = run;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public HandlerMeta meta() {
            return meta;
        }

        @Override
        public Outcome invoke(Invocation call) {
            Source data = run.data;
            assertThat(call.input().context().keySet())
                    .as("context is the server's")
                    .doesNotContain(POSTED);
            if (data.consumeInt(0, 3) == 0) return Outcome.fail(data.consumeBoolean() ? "invalid_credentials" : "x");
            @Nullable String subject = meta.bindsSubject() ? run.pick(SUBJECTS) : null;
            // A handler may prove less than it declares (a passkey without user verification); more
            // breaks the handler contract, which the engine refuses loudly (Engine.proven)
            @Nullable Set<String> amr = null;
            if (data.consumeBoolean()) {
                amr = new HashSet<>();
                for (String a : meta.amr()) {
                    if (data.consumeBoolean()) amr.add(a);
                }
                // An Ok proves something: an empty claim is the whole declared set
                if (amr.isEmpty()) amr = null;
            }
            if (subject != null && !meta.amr().isEmpty()) {
                Set<String> declared = new HashSet<>(meta.amr());
                if (amr != null) declared.retainAll(amr);
                run.proved.computeIfAbsent(subject, s -> new HashSet<>()).addAll(declared);
            }
            Map<String, Object> user = new LinkedHashMap<>();
            if (meta.identifies()) {
                user.put("identifier", "alice@example.com");
                user.put("email", "alice@example.com");
                user.put("exists", data.consumeBoolean());
                user.put("has_totp", data.consumeBoolean());
                user.put("has_passkey", data.consumeBoolean());
            }
            List<Effect> effects = meta.effect() == HandlerMeta.EffectKind.WRITE && data.consumeBoolean()
                    ? List.of(new Effect(name, Map.of("id", "e" + run.commits.size())))
                    : List.of();
            return new Outcome.Ok(user, subject, amr, effects);
        }

        @Override
        public @Nullable String commit(Effect effect) {
            run.commits.add(name + ":" + effect.data().get("id"));
            return null;
        }
    }

    /** Hooks return what the fuzzer picks: valid output, attempts to sign someone in, or junk. */
    static final class FuzzedHooks implements HookRunner {
        final Run run;

        FuzzedHooks(Run run) {
            this.run = run;
        }

        @Override
        public @Nullable Object run(String tenantId, faye.ir.Hook hook, Map<String, Object> ctx) {
            Source data = run.data;
            assertThat(ctx).as("a hook never sees auth").doesNotContainKey("auth");
            return switch (data.consumeInt(0, 7)) {
                case 0 -> Map.of("set", Map.of("risk", data.consumeBoolean() ? "high" : "low"));
                case 1 -> Map.of("fail", "blocked");
                case 2 -> {
                    List<?> options = ctx.get("options") instanceof List<?> l ? l : List.of();
                    Object pick = options.isEmpty() ? "none" : options.get(data.consumeInt(0, options.size() - 1));
                    yield Map.of(
                            "pick", pick instanceof Map<?, ?> m ? String.valueOf(m.get("id")) : String.valueOf(pick));
                }
                case 3 -> Map.of("set", Map.of("auth", Map.of("subject", "bob"), "subject", "bob"));
                case 4 -> Map.of("subject", "bob", "amr", List.of("pwd", "otp"), "acr", Acr.TWO_FACTOR);
                case 5 -> throw new HookException("boom");
                case 6 -> null;
                default -> run.value(2);
            };
        }
    }

    /** The in memory store, with snapshots for O6 and a lost race on demand. */
    static final class Store implements FlowStore {
        final InMemoryFlowStore inner = new InMemoryFlowStore();
        final Map<String, String> subjects = new LinkedHashMap<>();
        boolean loseNext;

        String snapshot(String flowId) {
            return inner.load(flowId).map(FlowStateCodec::write).orElse("");
        }

        void roundTrip(String flowId) {
            FlowState now = inner.load(flowId).orElseThrow();
            FlowState read = FlowStateCodec.read(FlowStateCodec.write(now)).orElseThrow();
            assertThat(read).isEqualTo(now);
        }

        @Override
        public Optional<FlowState> load(String flowId) {
            return inner.load(flowId);
        }

        @Override
        public void create(FlowState state) {
            inner.create(state);
        }

        @Override
        public boolean replace(FlowState next) {
            if (loseNext) {
                loseNext = false;
                return false;
            }
            return inner.replace(next);
        }
    }

    // ---- flows ----

    static @Nullable Flow flow(Source data) {
        if (data.consumeInt(0, 3) == 0) return CONFORMANCE.get(data.consumeInt(0, CONFORMANCE.size() - 1));
        ArrayNode flow =
                NODES.arrayNode().add("flow").add(IrCodec.FORMAT).add("g").add(1);
        flow.add(entry("login", "authenticate", new Gen(data, true).steps(3)));
        if (data.consumeBoolean()) flow.add(entry("consent", "consent", new Gen(data, false).steps(2)));
        try {
            return IrCodec.readFlow(flow);
        } catch (IrException e) {
            return null;
        }
    }

    static ArrayNode entry(String id, String kind, ArrayNode body) {
        return NODES.arrayNode().add("entry").add(id).add(kind).add(body);
    }

    /** Generates IR from the grammar of {@code schema/ir.schema.json}, bounded in size. */
    static final class Gen {
        static final List<String> HANDLERS = List.of(
                "identity.resolve",
                "password.verify",
                "totp.verify",
                "totp.enroll",
                "webauthn.assert",
                "webauthn.register",
                "consent.record");
        static final List<String> SCREENS =
                List.of("identifier", "password", "passkey", "otp", "otp_enroll", "consent", "method_picker", "error");
        /** Screen paths (ADR 0029): the first six fit some screens, the last five fit none. */
        static final List<String> SCREEN_PATHS = List.of(
                "/login",
                "/login/who",
                "/challenge",
                "/challenge/mfa/totp",
                "/consent",
                "/error/locked",
                "/challenge/a/b/c/d",
                "/Challenge/x",
                "/challenge/../login",
                "challenge/x",
                "/flow");

        static final List<String> PATHS = List.of(
                "user.has_totp",
                "user.has_passkey",
                "user.exists",
                "user.identified_by",
                "vars.risk",
                "client.first_party",
                "auth.acr",
                "auth.amr",
                "host.acr_values",
                "tenant.mfa_required");

        final Source data;
        final boolean login;
        int budget = 24;
        int ids;

        Gen(Source data, boolean login) {
            this.data = data;
            this.login = login;
        }

        ArrayNode steps(int max) {
            ArrayNode seq = NODES.arrayNode().add("seq");
            int n = data.consumeInt(1, max);
            for (int i = 0; i < n; i++) {
                seq.add(NODES.arrayNode().add("step").add(id("s")).add(node(3)));
            }
            return seq.add(NODES.arrayNode().add("succeed"));
        }

        ArrayNode node(int depth) {
            budget--;
            int form = depth <= 0 || budget <= 0 ? data.consumeInt(0, 3) : data.consumeInt(0, 9);
            return switch (form) {
                case 0, 1 -> interaction();
                case 2 -> NODES.arrayNode().add("set").add("risk").add(data.consumeBoolean() ? "high" : "low");
                case 3 -> NODES.arrayNode().add("call").add(pick(HANDLERS));
                case 4 -> NODES.arrayNode().add("when").add(predicate(2)).add(node(depth - 1));
                case 5 ->
                    NODES.arrayNode().add("retry").add(data.consumeInt(1, 3)).add(node(depth - 1));
                case 6 -> {
                    ArrayNode options = NODES.arrayNode();
                    int n = data.consumeInt(1, 3);
                    for (int i = 0; i < n; i++) {
                        ArrayNode opt =
                                NODES.arrayNode().add("opt").add(id("o")).add(node(depth - 1));
                        if (data.consumeBoolean()) opt.add(NODES.objectNode().set("guard", predicate(1)));
                        options.add(opt);
                    }
                    ArrayNode choose = NODES.arrayNode().add("choose").add(options);
                    if (data.consumeInt(0, 3) == 0) {
                        choose.add(NODES.objectNode().set("pick", hook()));
                    }
                    yield choose;
                }
                case 7 -> {
                    ArrayNode seq = NODES.arrayNode().add("seq");
                    int n = data.consumeInt(1, 3);
                    for (int i = 0; i < n; i++) seq.add(node(depth - 1));
                    yield seq;
                }
                case 8 ->
                    data.consumeBoolean()
                            ? hook()
                            : NODES.arrayNode().add("fail").add("blocked");
                default -> NODES.arrayNode().add("step").add(id("s")).add(node(depth - 1));
            };
        }

        /** A screen and the handler that consumes its answer, the common shape. */
        ArrayNode interaction() {
            String screen = pick(SCREENS);
            ObjectNode opts = NODES.objectNode();
            if ("identifier".equals(screen)) {
                switch (data.consumeInt(0, 2)) {
                    case 1 -> opts.put("passkey", true);
                    case 2 -> opts.put("password", true);
                    default -> {}
                }
            }
            if ("passkey".equals(screen) && data.consumeBoolean()) opts.put("register", true);
            if (data.consumeInt(0, 3) == 0) opts.put("path", pick(SCREEN_PATHS));
            ArrayNode node = NODES.arrayNode().add("screen").add(screen);
            if (!opts.isEmpty()) node.add(opts);
            ArrayNode call = NODES.arrayNode().add("call").add(login ? pick(HANDLERS) : "consent.record");
            return NODES.arrayNode().add("seq").add(node).add(call);
        }

        ArrayNode hook() {
            return NODES.arrayNode().add("hook").add("h").add(HOOK.hash());
        }

        JsonNode predicate(int depth) {
            return switch (depth <= 0 ? 0 : data.consumeInt(0, 5)) {
                case 0 -> NODES.textNode(pick(PATHS));
                case 1 -> NODES.objectNode().set("not", predicate(depth - 1));
                case 2 ->
                    NODES.objectNode()
                            .set(
                                    "and",
                                    NODES.arrayNode().add(predicate(depth - 1)).add(predicate(depth - 1)));
                case 3 ->
                    NODES.objectNode()
                            .set(
                                    "or",
                                    NODES.arrayNode().add(predicate(depth - 1)).add(predicate(depth - 1)));
                case 4 ->
                    NODES.objectNode()
                            .set(
                                    "eq",
                                    NODES.arrayNode()
                                            .add(pick(PATHS))
                                            .add(pick(List.of("high", "passkey", Acr.TWO_FACTOR))));
                default ->
                    NODES.objectNode()
                            .set(
                                    "has",
                                    NODES.arrayNode()
                                            .add(pick(List.of("auth.amr", "host.acr_values")))
                                            .add(pick(List.of("pwd", "hwk", Acr.TWO_FACTOR))));
            };
        }

        String id(String prefix) {
            return prefix + ids++;
        }

        <T> T pick(List<T> list) {
            return list.get(data.consumeInt(0, list.size() - 1));
        }
    }

    /**
     * What the harness draws its choices from: the fuzzer's data, or bytes from a seeded random
     * source for the sweep (Jazzer's own provider only exists inside its agent). Exhausted, every
     * choice is its lowest value, so every input ends.
     */
    interface Source {
        int consumeInt(int min, int max);

        long consumeLong();

        double consumeRegularDouble();

        String consumeString(int maxLength);

        String consumeAsciiString(int maxLength);

        int remainingBytes();

        default boolean consumeBoolean() {
            return consumeInt(0, 1) == 1;
        }

        record Fuzzed(FuzzedDataProvider fdp) implements Source {
            @Override
            public int consumeInt(int min, int max) {
                return fdp.consumeInt(min, max);
            }

            @Override
            public long consumeLong() {
                return fdp.consumeLong();
            }

            @Override
            public double consumeRegularDouble() {
                return fdp.consumeRegularDouble();
            }

            @Override
            public String consumeString(int maxLength) {
                return fdp.consumeString(maxLength);
            }

            @Override
            public String consumeAsciiString(int maxLength) {
                return fdp.consumeAsciiString(maxLength);
            }

            @Override
            public int remainingBytes() {
                return fdp.remainingBytes();
            }
        }

        /** Choices read from a byte array, front to back. */
        final class Bytes implements Source {
            private final byte[] bytes;
            private int at;

            Bytes(byte[] bytes) {
                this.bytes = bytes;
            }

            private int next() {
                return at < bytes.length ? bytes[at++] & 0xff : 0;
            }

            @Override
            public int consumeInt(int min, int max) {
                long range = (long) max - min + 1;
                long v = ((long) next() << 24 | next() << 16 | next() << 8 | next()) & 0xffffffffL;
                return (int) (min + v % range);
            }

            @Override
            public long consumeLong() {
                return (long) consumeInt(Integer.MIN_VALUE, Integer.MAX_VALUE) << 32
                        | (consumeInt(Integer.MIN_VALUE, Integer.MAX_VALUE) & 0xffffffffL);
            }

            @Override
            public double consumeRegularDouble() {
                double d = Double.longBitsToDouble(consumeLong());
                return Double.isFinite(d) ? d : 0.5;
            }

            @Override
            public String consumeString(int maxLength) {
                int n = consumeInt(0, maxLength);
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < n; i++) out.appendCodePoint(consumeInt(0, 0x2fff));
                return out.toString();
            }

            @Override
            public String consumeAsciiString(int maxLength) {
                int n = consumeInt(0, maxLength);
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < n; i++) out.append((char) consumeInt(0x20, 0x7e));
                return out.toString();
            }

            @Override
            public int remainingBytes() {
                return bytes.length - at;
            }
        }
    }

    static List<Flow> conformanceFlows() {
        try (Stream<Path> files = Files.list(Fixtures.CONFORMANCE.resolve("flows"))) {
            List<Flow> out = new ArrayList<>();
            for (Path f : files.sorted().toList()) {
                Flow flow = IrCodec.readFlow(Files.readString(f));
                if (Checker.check(
                                flow,
                                Fixtures.handlers(),
                                Fixtures.screens(),
                                TenantPolicy.DEFAULT,
                                h -> Optional.of(HOOK))
                        .ok()) {
                    out.add(flow);
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
