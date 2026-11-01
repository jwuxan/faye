package faye.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import faye.engine.FlowException.Code;
import faye.host.mock.MockFlowHost;
import faye.ir.IrCodec;
import faye.screens.FixedIssuers;
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
        return Engine.builder()
                .versions(versions)
                .handlers(new HandlerRegistry(List.of(resolve, verify, unused("consent.record"))))
                .store(store)
                .host(host)
                .screens(FixedIssuers.registry())
                .build();
    }

    @Test
    void progressSurvivesANewEngineInstance() {
        var first = (ScreenView) engine().start(HostKind.LOGIN, "c1", Map.of(), "binder");
        var password = (ScreenView) engine().submit(
                        first.flowId(),
                        "binder",
                        first.nonce(),
                        new Submission(Map.of("identifier", "alice@example.com")));

        // A fresh engine shares nothing with the previous one except the store.
        Engine restarted = engine();
        assertThat(restarted.view(first.flowId(), "binder")).isEqualTo(password);
        assertThat(password.screen()).containsEntry("identifier", "alice@example.com");

        var done = restarted.submit(
                first.flowId(), "binder", password.nonce(), new Submission(Map.of("password", "right")));
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
        var first = (ScreenView) engine.start(HostKind.LOGIN, "c1", Map.of(), "binder");
        var identifier = new Submission(Map.of("identifier", "alice@example.com"));
        engine.submit(first.flowId(), "binder", first.nonce(), identifier);

        assertThatThrownBy(() -> engine.submit(first.flowId(), "binder", first.nonce(), identifier))
                .isInstanceOfSatisfying(
                        FlowException.class, e -> assertThat(e.code()).isEqualTo(Code.STALE_NONCE));
    }

    @Test
    void concurrentDoubleSubmitAcceptsOnce() {
        var engine = engine();
        var first = (ScreenView) engine.start(HostKind.LOGIN, "c1", Map.of(), "binder");
        var password = (ScreenView) engine.submit(
                first.flowId(), "binder", first.nonce(), new Submission(Map.of("identifier", "alice@example.com")));
        var answer = new Submission(Map.of("password", "right"));

        // Both requests read the same revision before either saves: b's first load is stale, and
        // every later load sees what is stored, as the FlowStore contract says.
        FlowStore racing = new FlowStore() {
            final FlowState snapshot = store.load(first.flowId()).orElseThrow();
            boolean stale = true;

            @Override
            public java.util.Optional<FlowState> load(String id) {
                if (stale) {
                    stale = false;
                    return java.util.Optional.of(snapshot);
                }
                return store.load(id);
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
        Engine a = Engine.builder()
                .versions(versions)
                .handlers(handlers())
                .store(store)
                .host(host)
                .screens(FixedIssuers.registry())
                .build();
        Engine b = Engine.builder()
                .versions(versions)
                .handlers(handlers())
                .store(racing)
                .host(host)
                .screens(FixedIssuers.registry())
                .build();

        // a finishes the flow; b, which read the screen before that, then loses the race
        assertThat(a.submit(first.flowId(), "binder", password.nonce(), answer)).isInstanceOf(Redirect.class);
        assertThatThrownBy(() -> b.submit(first.flowId(), "binder", password.nonce(), answer))
                .isInstanceOfSatisfying(
                        FlowException.class, e -> assertThat(e.code()).isEqualTo(Code.FLOW_FINISHED));
        assertThat(host.accepted()).hasSize(1);
    }

    @Test
    void finishedFlowCannotBeResumed() {
        var engine = engine();
        var first = (ScreenView) engine.start(HostKind.LOGIN, "c1", Map.of(), "binder");
        var password = (ScreenView) engine.submit(
                first.flowId(), "binder", first.nonce(), new Submission(Map.of("identifier", "alice@example.com")));
        engine.submit(first.flowId(), "binder", password.nonce(), new Submission(Map.of("password", "wrong")));

        assertThat(host.rejected()).singleElement().satisfies(r -> {
            assertThat(r.error()).isEqualTo("access_denied");
            assertThat(r.description()).isEqualTo("invalid_credentials");
        });
        assertThatThrownBy(() -> engine.view(first.flowId(), "binder"))
                .isInstanceOfSatisfying(
                        FlowException.class, e -> assertThat(e.code()).isEqualTo(Code.FLOW_FINISHED));
        assertThatThrownBy(() -> engine.submit(
                        first.flowId(), "binder", password.nonce(), new Submission(Map.of("password", "right"))))
                .isInstanceOfSatisfying(
                        FlowException.class, e -> assertThat(e.code()).isEqualTo(Code.FLOW_FINISHED));
    }

    @Test
    void invalidSubmissionLeavesStateUnchanged() {
        var engine = engine();
        var first = (ScreenView) engine.start(HostKind.LOGIN, "c1", Map.of(), "binder");
        for (var bad : List.of(
                Map.<String, Object>of(),
                Map.<String, Object>of("identifier", " "),
                Map.<String, Object>of("password", "x"),
                Map.<String, Object>of("identifier", "a", "extra", "b"))) {
            assertThatThrownBy(() -> engine.submit(first.flowId(), "binder", first.nonce(), new Submission(bad)))
                    .isInstanceOfSatisfying(
                            FlowException.class, e -> assertThat(e.code()).isEqualTo(Code.INVALID_SUBMISSION));
        }
        assertThat(engine.view(first.flowId(), "binder")).isEqualTo(first);
    }

    @Test
    void unknownFlowIsNotFound() {
        assertThatThrownBy(() -> engine().view("f_missing", "binder"))
                .isInstanceOfSatisfying(
                        FlowException.class, e -> assertThat(e.code()).isEqualTo(Code.FLOW_NOT_FOUND));
    }

    @Test
    void persistedStateNeverContainsTheSubmission() {
        var engine = engine();
        var first = (ScreenView) engine.start(HostKind.LOGIN, "c1", Map.of(), "binder");
        var password = (ScreenView) engine.submit(
                first.flowId(), "binder", first.nonce(), new Submission(Map.of("identifier", "alice@example.com")));
        engine.submit(first.flowId(), "binder", password.nonce(), new Submission(Map.of("password", "hunter2-secret")));

        assertThat(FlowStateCodec.write(store.load(first.flowId()).orElseThrow()))
                .doesNotContain("hunter2-secret");
    }

    @Test
    void flowStaysPinnedToItsVersion() throws Exception {
        var engine = engine();
        var first = (ScreenView) engine.start(HostKind.LOGIN, "c1", Map.of(), "binder");
        String pinned = versions.current(Engine.DEFAULT_TENANT).orElseThrow().hash();

        versions.publish(
                Engine.DEFAULT_TENANT,
                FlowVersion.of(IrCodec.readFlow(Files.readString(FLOW.resolveSibling("explicit-fail.json")))));
        var password = (ScreenView) engine.submit(
                first.flowId(), "binder", first.nonce(), new Submission(Map.of("identifier", "alice@example.com")));
        engine.submit(first.flowId(), "binder", password.nonce(), new Submission(Map.of("password", "right")));

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
        Engine engine = Engine.builder()
                .versions(versions)
                .handlers(new HandlerRegistry(List.of(
                        handler("identity.resolve", Set.of(), false, in -> Outcome.ok()),
                        handler("password.verify", Set.of("pwd"), true, in -> Outcome.subject(subjects[calls[0]++])))))
                .store(store)
                .host(host)
                .screens(FixedIssuers.registry())
                .build();

        var s = (ScreenView) engine.start(HostKind.LOGIN, "c1", Map.of(), "binder");
        s = (ScreenView) engine.submit(s.flowId(), "binder", s.nonce(), new Submission(Map.of("identifier", "a")));
        s = (ScreenView) engine.submit(s.flowId(), "binder", s.nonce(), new Submission(Map.of("password", "a")));
        engine.submit(s.flowId(), "binder", s.nonce(), new Submission(Map.of("password", "b")));

        assertThat(host.accepted()).isEmpty();
        assertThat(host.rejected())
                .singleElement()
                .satisfies(r -> assertThat(r.description()).isEqualTo("subject_mismatch"));
    }

    @Test
    void tenantComesFromClientMetadata() throws Exception {
        versions.publish(
                "acme", FlowVersion.of(IrCodec.readFlow(Files.readString(FLOW.resolveSibling("explicit-fail.json")))));
        host.register(MockFlowHost.request(
                HostKind.LOGIN,
                "c-acme",
                Map.of("client_id", "acme-app", "metadata", Map.of("tenant", "acme")),
                HostParams.fromMap(Map.of())));
        var engine = engine();
        var s = (ScreenView) engine.start(HostKind.LOGIN, "c-acme", Map.of(), "binder");
        engine.submit(s.flowId(), "binder", s.nonce(), new Submission(Map.of("identifier", "a")));

        assertThat(host.rejected())
                .singleElement()
                .satisfies(r -> assertThat(r.description()).isEqualTo("blocked"));
    }

    @Test
    void rememberedSessionIsReusedWhenItMeetsTheRequestedAcr() {
        host.register(MockFlowHost.request(
                HostKind.LOGIN,
                "c-skip",
                Map.of("client_id", "demo"),
                HostParams.fromMap(Map.of("skip", true, "subject", "alice"))));
        assertThat(engine().start(HostKind.LOGIN, "c-skip", Map.of(), "binder")).isInstanceOf(Redirect.class);
        assertThat(host.accepted()).singleElement().satisfies(a -> {
            assertThat(a.outcome().subject()).isEqualTo("alice");
            assertThat(a.outcome().amr()).isEmpty();
            assertThat(a.outcome().acr()).isEqualTo(Acr.ONE_FACTOR);
        });
    }

    @Test
    void rememberedSessionStepsUpForTheSameSubjectOnly() {
        host.register(MockFlowHost.request(
                HostKind.LOGIN,
                "c-up",
                Map.of("client_id", "demo"),
                HostParams.fromMap(Map.of("skip", true, "subject", "mallory", "acr_values", List.of("faye:2fa")))));
        var engine = engine();
        var s = (ScreenView) engine.start(HostKind.LOGIN, "c-up", Map.of(), "binder");
        s = (ScreenView) engine.submit(s.flowId(), "binder", s.nonce(), new Submission(Map.of("identifier", "a")));
        engine.submit(s.flowId(), "binder", s.nonce(), new Submission(Map.of("password", "right")));

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
        var engine = Engine.builder()
                .versions(versions)
                .handlers(new HandlerRegistry(List.of(resolve, verify, record)))
                .store(store)
                .host(host)
                .screens(FixedIssuers.registry())
                .build();

        var s = (ScreenView) engine.start(HostKind.LOGIN, "c1", Map.of(), "binder");
        s = (ScreenView) engine.submit(s.flowId(), "binder", s.nonce(), new Submission(Map.of("identifier", "a")));
        engine.submit(s.flowId(), "binder", s.nonce(), new Submission(Map.of("password", "right")));
        var login = host.accepted().get(0).outcome();

        // A new version is published between login and consent; consent still runs the login's.
        versions.publish(Engine.DEFAULT_TENANT, FlowVersion.of(IrCodec.readFlow(Files.readString(FLOW))));
        host.register(MockFlowHost.request(
                HostKind.CONSENT,
                "k1",
                Map.of("client_id", "partner-app", "first_party", false),
                HostParams.fromMap(Map.of(
                        "subject",
                        "alice",
                        "requested_scope",
                        List.of("openid", "email"),
                        "context",
                        login.context()))));
        var consent = (ScreenView) engine.start(HostKind.CONSENT, "k1", Map.of(), "binder");
        assertThat(consent.screen())
                .containsEntry("type", "consent")
                .containsEntry("client", Map.of("id", "partner-app"))
                .containsEntry("scopes", List.of("openid", "email"));
        engine.submit(consent.flowId(), "binder", consent.nonce(), new Submission(Map.of("decision", "allow")));

        assertThat(host.consented()).singleElement().satisfies(c -> {
            assertThat(c.outcome().subject()).isEqualTo("alice");
            assertThat(c.outcome().grantScope()).containsExactly("openid", "email");
            // What the login learned goes to the host, which decides the claims
            assertThat(c.outcome().user()).containsEntry("email", "alice@example.com");
        });
    }

    @Test
    void consentForAnotherSubjectDoesNotSeeTheLogin() throws Exception {
        versions.publish(
                Engine.DEFAULT_TENANT,
                FlowVersion.of(IrCodec.readFlow(Files.readString(FLOW.resolveSibling("partner-consent.json")))));
        var engine = engine();
        var s = (ScreenView) engine.start(HostKind.LOGIN, "c1", Map.of(), "binder");
        s = (ScreenView) engine.submit(
                s.flowId(), "binder", s.nonce(), new Submission(Map.of("identifier", "alice@example.com")));
        engine.submit(s.flowId(), "binder", s.nonce(), new Submission(Map.of("password", "right")));

        host.register(MockFlowHost.request(
                HostKind.CONSENT,
                "k1",
                Map.of("client_id", "demo", "first_party", true),
                HostParams.fromMap(Map.of(
                        "subject",
                        "bob",
                        "requested_scope",
                        List.of("email"),
                        "context",
                        host.accepted().get(0).outcome().context()))));
        engine.start(HostKind.CONSENT, "k1", Map.of(), "binder");
        assertThat(host.consented())
                .singleElement()
                .satisfies(c -> assertThat(c.outcome().user()).isEmpty());
    }

    @Test
    void consentForAnotherTenantOrAPreviewDoesNotSeeTheLogin() throws Exception {
        versions.publish(
                Engine.DEFAULT_TENANT,
                FlowVersion.of(IrCodec.readFlow(Files.readString(FLOW.resolveSibling("partner-consent.json")))));
        versions.publish(
                "acme",
                FlowVersion.of(IrCodec.readFlow(Files.readString(FLOW.resolveSibling("partner-consent.json")))));
        var engine = engine();
        var s = (ScreenView) engine.start(HostKind.LOGIN, "c1", Map.of(), "binder");
        s = (ScreenView) engine.submit(
                s.flowId(), "binder", s.nonce(), new Submission(Map.of("identifier", "alice@example.com")));
        engine.submit(s.flowId(), "binder", s.nonce(), new Submission(Map.of("password", "right")));
        Map<String, Object> context = host.accepted().get(0).outcome().context();
        HostParams params = HostParams.fromMap(
                Map.of("subject", "alice", "requested_scope", List.of("email"), "context", context, "skip", true));

        // The same subject and a relayed context, but the host decided another tenant, or a preview
        host.register(new HostRequest(HostKind.CONSENT, "k-acme", "acme", false, Map.of("client_id", "x"), params));
        host.register(new HostRequest(HostKind.CONSENT, "k-preview", null, true, Map.of("client_id", "x"), params));
        engine.start(HostKind.CONSENT, "k-acme", Map.of(), "binder");
        engine.start(HostKind.CONSENT, "k-preview", Map.of(), "binder");

        // acme's consent is granted, with nothing of the default tenant's login; the preview one is
        // refused, since no draft runs for it
        assertThat(host.consented())
                .singleElement()
                .satisfies(c -> assertThat(c.outcome().user()).isEmpty());
        assertThat(host.rejected())
                .singleElement()
                .satisfies(r -> assertThat(r.description()).isEqualTo("preview_unavailable"));
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
        var engine = Engine.builder()
                .versions(versions)
                .handlers(new HandlerRegistry(List.of(
                        resolve,
                        assertPasskey,
                        unused("password.verify"),
                        unused("totp.verify"),
                        unused("totp.enroll"),
                        unused("webauthn.register"),
                        unused("consent.record"))))
                .store(store)
                .host(host)
                .screens(FixedIssuers.registry())
                .build();

        var s = (ScreenView) engine.start(HostKind.LOGIN, "c1", Map.of(), "binder");
        // The autofilled passkey fails; with no retry around it the section fails rather than
        // replaying the same answer.
        engine.submit(s.flowId(), "binder", s.nonce(), new Submission(Map.of("passkey", Map.of("id", "x"))));
        assertThat(calls[0]).isEqualTo(1);
        assertThat(host.rejected())
                .singleElement()
                .satisfies(r -> assertThat(r.description()).isEqualTo("invalid_credentials"));
    }

    /** A consent whose version has no single consent entry is not granted: it fails closed. */
    @Test
    void consentOnAVersionWithTwoConsentEntriesIsRefused() {
        // The checker refuses such a version; an unchecked one must still not grant
        versions.publish(
                Engine.DEFAULT_TENANT,
                FlowVersion.of(IrCodec.readFlow("[\"flow\",1,\"f\",1,"
                        + "[\"entry\",\"login\",\"authenticate\",[\"succeed\"]],"
                        + "[\"entry\",\"a\",\"consent\",[\"succeed\"]],[\"entry\",\"b\",\"consent\",[\"succeed\"]]]")));
        host.register(MockFlowHost.request(
                HostKind.CONSENT,
                "k2",
                Map.of("client_id", "demo"),
                HostParams.fromMap(Map.of("subject", "alice", "requested_scope", List.of()))));
        engine().start(HostKind.CONSENT, "k2", Map.of(), "binder");
        assertThat(host.consented()).isEmpty();
        assertThat(host.rejected())
                .singleElement()
                .satisfies(r -> assertThat(r.description()).isEqualTo(Engine.FLOW_UNAVAILABLE));
    }

    /** A consent whose linked login ran a version that can no longer be read does not run another one. */
    @Test
    void consentForALoginWhoseVersionIsGoneIsRefused() {
        var engine = engine();
        var s = (ScreenView) engine.start(HostKind.LOGIN, "c1", Map.of(), "binder");
        s = (ScreenView) engine.submit(s.flowId(), "binder", s.nonce(), new Submission(Map.of("identifier", "a")));
        engine.submit(s.flowId(), "binder", s.nonce(), new Submission(Map.of("password", "right")));
        var login = host.accepted().get(0).outcome();

        // The same stores, but the login's version is no longer readable
        FlowVersions forgetful = new FlowVersions() {
            @Override
            public FlowVersion publish(String tenantId, FlowVersion version) {
                return versions.publish(tenantId, version);
            }

            @Override
            public void store(FlowVersion version) {
                versions.store(version);
            }

            @Override
            public java.util.Optional<FlowVersion> current(String tenantId) {
                return versions.current(tenantId);
            }

            @Override
            public java.util.Optional<FlowVersion> get(String hash) {
                return java.util.Optional.empty();
            }

            @Override
            public List<String> history(String tenantId) {
                return versions.history(tenantId);
            }

            @Override
            public boolean rollback(String tenantId, String hash) {
                return versions.rollback(tenantId, hash);
            }
        };
        host.register(MockFlowHost.request(
                HostKind.CONSENT,
                "k1",
                Map.of("client_id", "demo"),
                HostParams.fromMap(
                        Map.of("subject", "alice", "requested_scope", List.of(), "context", login.context()))));
        Engine.builder()
                .versions(forgetful)
                .handlers(handlers())
                .store(store)
                .host(host)
                .screens(FixedIssuers.registry())
                .build()
                .start(HostKind.CONSENT, "k1", Map.of(), "binder");
        assertThat(host.consented()).isEmpty();
        assertThat(host.rejected())
                .singleElement()
                .satisfies(r -> assertThat(r.description()).isEqualTo(Engine.FLOW_UNAVAILABLE));
    }

    // ---- helpers ----

    HandlerRegistry handlers() {
        return new HandlerRegistry(List.of(
                handler(
                        "identity.resolve",
                        Set.of(),
                        false,
                        in -> Outcome.user(Map.of("identifier", in.string("identifier")))),
                handler("password.verify", Set.of("pwd"), true, in -> Outcome.subject("alice")),
                unused("consent.record")));
    }

    /** A handler a flow names but the test never reaches; the engine runs only flows whose handlers all exist. */
    static Handler unused(String name) {
        return handler(name, Set.of(), false, in -> {
            throw new AssertionError(name + " was not expected to run");
        });
    }

    static Handler handler(
            String name, Set<String> amr, boolean binds, java.util.function.Function<Submission, Outcome> f) {
        return new Handler() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public HandlerMeta meta() {
                return new HandlerMeta(amr, binds, false, false, HandlerMeta.EffectKind.NONE, HandlerMeta.NO_ARGS);
            }

            @Override
            public Outcome invoke(Invocation call) {
                return f.apply(call.input());
            }
        };
    }
}
