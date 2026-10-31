package faye.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import faye.engine.FlowException.Code;
import faye.host.mock.MockFlowHost;
import faye.ir.IrCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EngineTest {

    static final Path FLOW = Path.of(System.getProperty("faye.repoRoot"), "conformance/flows/default-login.json");

    final FlowVersions versions = new InMemoryFlowVersions();
    final InMemoryFlowStore store = new InMemoryFlowStore();
    final MockFlowHost host = new MockFlowHost();

    @BeforeEach
    void publish() throws Exception {
        versions.publish(Engine.DEFAULT_TENANT, FlowVersion.of(IrCodec.readFlow(Files.readString(FLOW))));
    }

    /** identity.resolve records the identifier; password.verify accepts "right" for alice. */
    Engine engine() {
        Handler resolve = handler(
                "identity.resolve", Set.of(), false, in -> Outcome.user(Map.of("identifier", in.string("identifier"))));
        Handler verify = handler(
                "password.verify",
                Set.of("pwd"),
                true,
                in -> "right".equals(in.string("password"))
                        ? Outcome.subject("alice")
                        : Outcome.fail("invalid_credentials"));
        return new Engine(versions, new HandlerRegistry(List.of(resolve, verify)), store, host);
    }

    @Test
    void progressSurvivesANewEngineInstance() {
        var first = (ScreenView) engine().start(HostKind.LOGIN, "c1", Map.of());
        var password = (ScreenView) engine().submit(
                        first.flowId(), first.nonce(), new Submission(Map.of("identifier", "alice@example.com")));

        // A fresh engine shares nothing with the previous one except the store.
        Engine restarted = engine();
        assertThat(restarted.view(first.flowId())).isEqualTo(password);
        assertThat(password.screen()).containsEntry("identifier", "alice@example.com");

        var done = restarted.submit(first.flowId(), password.nonce(), new Submission(Map.of("password", "right")));
        assertThat(done).isInstanceOf(Redirect.class);
        assertThat(host.accepted()).singleElement().satisfies(a -> {
            assertThat(a.outcome().subject()).isEqualTo("alice");
            assertThat(a.outcome().amr()).containsExactly("pwd");
            assertThat(a.outcome().context())
                    .containsEntry("flowId", first.flowId())
                    .containsEntry(
                            "versionHash",
                            versions.current(Engine.DEFAULT_TENANT)
                                    .orElseThrow()
                                    .hash());
        });
    }

    @Test
    void replayedNonceIsRefused() {
        var engine = engine();
        var first = (ScreenView) engine.start(HostKind.LOGIN, "c1", Map.of());
        var identifier = new Submission(Map.of("identifier", "alice@example.com"));
        engine.submit(first.flowId(), first.nonce(), identifier);

        assertThatThrownBy(() -> engine.submit(first.flowId(), first.nonce(), identifier))
                .isInstanceOfSatisfying(
                        FlowException.class, e -> assertThat(e.code()).isEqualTo(Code.STALE_NONCE));
    }

    @Test
    void concurrentDoubleSubmitAcceptsOnce() {
        var engine = engine();
        var first = (ScreenView) engine.start(HostKind.LOGIN, "c1", Map.of());
        var password = (ScreenView)
                engine.submit(first.flowId(), first.nonce(), new Submission(Map.of("identifier", "alice@example.com")));
        var answer = new Submission(Map.of("password", "right"));

        // Both requests read the same revision before either saves.
        FlowStore racing = new FlowStore() {
            final FlowState snapshot = store.load(first.flowId()).orElseThrow();

            @Override
            public java.util.Optional<FlowState> load(String id) {
                return java.util.Optional.of(snapshot);
            }

            @Override
            public void create(FlowState s) {
                store.create(s);
            }

            @Override
            public boolean replace(FlowState next) {
                return store.replace(next);
            }
        };
        Engine a = new Engine(versions, handlers(), racing, host);
        Engine b = new Engine(versions, handlers(), racing, host);

        assertThat(a.submit(first.flowId(), password.nonce(), answer)).isInstanceOf(Redirect.class);
        assertThatThrownBy(() -> b.submit(first.flowId(), password.nonce(), answer))
                .isInstanceOfSatisfying(
                        FlowException.class, e -> assertThat(e.code()).isEqualTo(Code.CONFLICT));
        assertThat(host.accepted()).hasSize(1);
    }

    @Test
    void finishedFlowCannotBeResumed() {
        var engine = engine();
        var first = (ScreenView) engine.start(HostKind.LOGIN, "c1", Map.of());
        var password = (ScreenView)
                engine.submit(first.flowId(), first.nonce(), new Submission(Map.of("identifier", "alice@example.com")));
        engine.submit(first.flowId(), password.nonce(), new Submission(Map.of("password", "wrong")));

        assertThat(host.rejected()).singleElement().satisfies(r -> {
            assertThat(r.error()).isEqualTo("access_denied");
            assertThat(r.description()).isEqualTo("invalid_credentials");
        });
        assertThatThrownBy(() -> engine.view(first.flowId()))
                .isInstanceOfSatisfying(
                        FlowException.class, e -> assertThat(e.code()).isEqualTo(Code.FLOW_FINISHED));
        assertThatThrownBy(() ->
                        engine.submit(first.flowId(), password.nonce(), new Submission(Map.of("password", "right"))))
                .isInstanceOfSatisfying(
                        FlowException.class, e -> assertThat(e.code()).isEqualTo(Code.FLOW_FINISHED));
    }

    @Test
    void invalidSubmissionLeavesStateUnchanged() {
        var engine = engine();
        var first = (ScreenView) engine.start(HostKind.LOGIN, "c1", Map.of());
        for (var bad : List.of(
                Map.<String, Object>of(),
                Map.<String, Object>of("identifier", " "),
                Map.<String, Object>of("password", "x"),
                Map.<String, Object>of("identifier", "a", "extra", "b"))) {
            assertThatThrownBy(() -> engine.submit(first.flowId(), first.nonce(), new Submission(bad)))
                    .isInstanceOfSatisfying(
                            FlowException.class, e -> assertThat(e.code()).isEqualTo(Code.INVALID_SUBMISSION));
        }
        assertThat(engine.view(first.flowId())).isEqualTo(first);
    }

    @Test
    void unknownFlowIsNotFound() {
        assertThatThrownBy(() -> engine().view("f_missing"))
                .isInstanceOfSatisfying(
                        FlowException.class, e -> assertThat(e.code()).isEqualTo(Code.FLOW_NOT_FOUND));
    }

    @Test
    void persistedStateNeverContainsTheSubmission() {
        var engine = engine();
        var first = (ScreenView) engine.start(HostKind.LOGIN, "c1", Map.of());
        var password = (ScreenView)
                engine.submit(first.flowId(), first.nonce(), new Submission(Map.of("identifier", "alice@example.com")));
        engine.submit(first.flowId(), password.nonce(), new Submission(Map.of("password", "hunter2-secret")));

        assertThat(FlowStateCodec.write(store.load(first.flowId()).orElseThrow()))
                .doesNotContain("hunter2-secret");
    }

    @Test
    void flowStaysPinnedToItsVersion() throws Exception {
        var engine = engine();
        var first = (ScreenView) engine.start(HostKind.LOGIN, "c1", Map.of());
        String pinned = versions.current(Engine.DEFAULT_TENANT).orElseThrow().hash();

        versions.publish(
                Engine.DEFAULT_TENANT,
                FlowVersion.of(IrCodec.readFlow(Files.readString(FLOW.resolveSibling("explicit-fail.json")))));
        var password = (ScreenView)
                engine.submit(first.flowId(), first.nonce(), new Submission(Map.of("identifier", "alice@example.com")));
        engine.submit(first.flowId(), password.nonce(), new Submission(Map.of("password", "right")));

        assertThat(host.accepted())
                .singleElement()
                .satisfies(a -> assertThat(a.outcome().context()).containsEntry("versionHash", pinned));
    }

    @Test
    void credentialForADifferentSubjectFails() throws Exception {
        versions.publish(
                Engine.DEFAULT_TENANT,
                FlowVersion.of(IrCodec.readFlow(Files.readString(FLOW.resolveSibling("two-credentials.json")))));
        String[] subjects = {"alice", "mallory"};
        int[] calls = {0};
        Engine engine = new Engine(
                versions,
                new HandlerRegistry(List.of(
                        handler("identity.resolve", Set.of(), false, in -> Outcome.ok()),
                        handler("password.verify", Set.of("pwd"), true, in -> Outcome.subject(subjects[calls[0]++])))),
                store,
                host);

        var s = (ScreenView) engine.start(HostKind.LOGIN, "c1", Map.of());
        s = (ScreenView) engine.submit(s.flowId(), s.nonce(), new Submission(Map.of("identifier", "a")));
        s = (ScreenView) engine.submit(s.flowId(), s.nonce(), new Submission(Map.of("password", "a")));
        engine.submit(s.flowId(), s.nonce(), new Submission(Map.of("password", "b")));

        assertThat(host.accepted()).isEmpty();
        assertThat(host.rejected())
                .singleElement()
                .satisfies(r -> assertThat(r.description()).isEqualTo("subject_mismatch"));
    }

    @Test
    void tenantComesFromClientMetadata() throws Exception {
        versions.publish(
                "acme", FlowVersion.of(IrCodec.readFlow(Files.readString(FLOW.resolveSibling("explicit-fail.json")))));
        host.register(new HostRequest(
                HostKind.LOGIN,
                "c-acme",
                Map.of("client_id", "acme-app", "metadata", Map.of("tenant", "acme")),
                Map.of()));
        var engine = engine();
        var s = (ScreenView) engine.start(HostKind.LOGIN, "c-acme", Map.of());
        engine.submit(s.flowId(), s.nonce(), new Submission(Map.of("identifier", "a")));

        assertThat(host.rejected())
                .singleElement()
                .satisfies(r -> assertThat(r.description()).isEqualTo("blocked"));
    }

    @Test
    void rememberedSessionIsReusedWhenItMeetsTheRequestedAcr() {
        host.register(new HostRequest(
                HostKind.LOGIN, "c-skip", Map.of("client_id", "demo"), Map.of("skip", true, "subject", "alice")));
        assertThat(engine().start(HostKind.LOGIN, "c-skip", Map.of())).isInstanceOf(Redirect.class);
        assertThat(host.accepted()).singleElement().satisfies(a -> {
            assertThat(a.outcome().subject()).isEqualTo("alice");
            assertThat(a.outcome().amr()).isEmpty();
            assertThat(a.outcome().acr()).isEqualTo(Acr.ONE_FACTOR);
        });
    }

    @Test
    void rememberedSessionStepsUpForTheSameSubjectOnly() {
        host.register(new HostRequest(
                HostKind.LOGIN,
                "c-up",
                Map.of("client_id", "demo"),
                Map.of("skip", true, "subject", "mallory", "acr_values", List.of("faye:2fa"))));
        var engine = engine();
        var s = (ScreenView) engine.start(HostKind.LOGIN, "c-up", Map.of());
        s = (ScreenView) engine.submit(s.flowId(), s.nonce(), new Submission(Map.of("identifier", "a")));
        engine.submit(s.flowId(), s.nonce(), new Submission(Map.of("password", "right")));

        // The password proves alice, but Hydra's session is mallory's.
        assertThat(host.accepted()).isEmpty();
        assertThat(host.rejected())
                .singleElement()
                .satisfies(r -> assertThat(r.description()).isEqualTo("subject_mismatch"));
    }

    @Test
    void consentRunsTheLoginsVersionWithItsUserAndGrantsClaimsByScope() throws Exception {
        versions.publish(
                Engine.DEFAULT_TENANT,
                FlowVersion.of(IrCodec.readFlow(Files.readString(FLOW.resolveSibling("partner-consent.json")))));
        Handler resolve = handler(
                "identity.resolve",
                Set.of(),
                false,
                in -> Outcome.user(Map.of(
                        "identifier",
                        "alice@example.com",
                        "email",
                        "alice@example.com",
                        "email_verified",
                        true,
                        "name",
                        "Alice")));
        Handler verify = handler("password.verify", Set.of("pwd"), true, in -> Outcome.subject("alice"));
        Handler record = handler("consent.record", Set.of(), false, in -> Outcome.ok());
        var engine = new Engine(versions, new HandlerRegistry(List.of(resolve, verify, record)), store, host);

        var s = (ScreenView) engine.start(HostKind.LOGIN, "c1", Map.of());
        s = (ScreenView) engine.submit(s.flowId(), s.nonce(), new Submission(Map.of("identifier", "a")));
        engine.submit(s.flowId(), s.nonce(), new Submission(Map.of("password", "right")));
        var login = host.accepted().get(0).outcome();

        // A new version is published between login and consent; consent still runs the login's.
        versions.publish(Engine.DEFAULT_TENANT, FlowVersion.of(IrCodec.readFlow(Files.readString(FLOW))));
        host.register(new HostRequest(
                HostKind.CONSENT,
                "k1",
                Map.of("client_id", "partner-app", "first_party", false),
                Map.of("subject", "alice", "requested_scope", List.of("openid", "email"), "context", login.context())));
        var consent = (ScreenView) engine.start(HostKind.CONSENT, "k1", Map.of());
        assertThat(consent.screen())
                .containsEntry("type", "consent")
                .containsEntry("client", Map.of("id", "partner-app"))
                .containsEntry("scopes", List.of("openid", "email"));
        engine.submit(consent.flowId(), consent.nonce(), new Submission(Map.of("decision", "allow")));

        assertThat(host.consented()).singleElement().satisfies(c -> {
            assertThat(c.outcome().subject()).isEqualTo("alice");
            assertThat(c.outcome().grantScope()).containsExactly("openid", "email");
            // email scope only: no name
            assertThat(c.outcome().idTokenClaims())
                    .isEqualTo(Map.of("email", "alice@example.com", "email_verified", true));
        });
    }

    @Test
    void consentForAnotherSubjectDoesNotSeeTheLogin() throws Exception {
        versions.publish(
                Engine.DEFAULT_TENANT,
                FlowVersion.of(IrCodec.readFlow(Files.readString(FLOW.resolveSibling("partner-consent.json")))));
        var engine = engine();
        var s = (ScreenView) engine.start(HostKind.LOGIN, "c1", Map.of());
        s = (ScreenView)
                engine.submit(s.flowId(), s.nonce(), new Submission(Map.of("identifier", "alice@example.com")));
        engine.submit(s.flowId(), s.nonce(), new Submission(Map.of("password", "right")));

        host.register(new HostRequest(
                HostKind.CONSENT,
                "k1",
                Map.of("client_id", "demo", "first_party", true),
                Map.of(
                        "subject",
                        "bob",
                        "requested_scope",
                        List.of("email"),
                        "context",
                        host.accepted().get(0).outcome().context())));
        engine.start(HostKind.CONSENT, "k1", Map.of());
        assertThat(host.consented())
                .singleElement()
                .satisfies(c -> assertThat(c.outcome().idTokenClaims()).isEmpty());
    }

    @Test
    void hostedPasskeyAnswersOnlyOneScreen() throws Exception {
        versions.publish(
                Engine.DEFAULT_TENANT,
                FlowVersion.of(IrCodec.readFlow(Files.readString(FLOW.resolveSibling("passkey-totp.json")))));
        Handler resolve =
                handler("identity.resolve", Set.of(), false, in -> Outcome.user(Map.of("identified_by", "passkey")));
        int[] calls = {0};
        Handler assertPasskey = handler(
                "webauthn.assert",
                Set.of("hwk", "user"),
                true,
                in -> calls[0]++ == 0 ? Outcome.fail("invalid_credentials") : Outcome.subject("alice"));
        var engine = new Engine(versions, new HandlerRegistry(List.of(resolve, assertPasskey)), store, host);

        var s = (ScreenView) engine.start(HostKind.LOGIN, "c1", Map.of());
        // The autofilled passkey fails; with no retry around it the section fails rather than
        // replaying the same answer.
        engine.submit(s.flowId(), s.nonce(), new Submission(Map.of("passkey", Map.of("id", "x"))));
        assertThat(calls[0]).isEqualTo(1);
        assertThat(host.rejected())
                .singleElement()
                .satisfies(r -> assertThat(r.description()).isEqualTo("invalid_credentials"));
    }

    // ---- helpers ----

    HandlerRegistry handlers() {
        return new HandlerRegistry(List.of(
                handler(
                        "identity.resolve",
                        Set.of(),
                        false,
                        in -> Outcome.user(Map.of("identifier", in.string("identifier")))),
                handler("password.verify", Set.of("pwd"), true, in -> Outcome.subject("alice"))));
    }

    static Handler handler(
            String name, Set<String> amr, boolean binds, java.util.function.Function<Submission, Outcome> f) {
        return new Handler() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Set<String> amr() {
                return amr;
            }

            @Override
            public boolean bindsSubject() {
                return binds;
            }

            @Override
            public Outcome invoke(Env env, Submission input, Map<String, Object> args) {
                return f.apply(input);
            }
        };
    }
}
