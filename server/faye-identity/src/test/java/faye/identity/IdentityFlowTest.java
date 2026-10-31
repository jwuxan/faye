package faye.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.webauthn4j.data.AuthenticatorAssertionResponse;
import com.webauthn4j.data.AuthenticatorAttestationResponse;
import com.webauthn4j.data.AuthenticatorSelectionCriteria;
import com.webauthn4j.data.PublicKeyCredential;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
import com.webauthn4j.data.PublicKeyCredentialRpEntity;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.PublicKeyCredentialUserEntity;
import com.webauthn4j.data.ResidentKeyRequirement;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.test.EmulatorUtil;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;
import faye.engine.Base32;
import faye.engine.Engine;
import faye.engine.EngineResponse;
import faye.engine.FlowState;
import faye.engine.FlowVersion;
import faye.engine.FlowVersions;
import faye.engine.HandlerRegistry;
import faye.engine.HookRunner;
import faye.engine.HostKind;
import faye.engine.HostRequest;
import faye.engine.InMemoryFlowStore;
import faye.engine.InMemoryFlowVersions;
import faye.engine.Previews;
import faye.engine.Redirect;
import faye.engine.ScreenView;
import faye.engine.Submission;
import faye.engine.TenantPolicies;
import faye.engine.TraceEvent;
import faye.host.mock.MockFlowHost;
import faye.ir.IrCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The real identity handlers driving the shipped passkey-totp flow through the engine: password
 * plus TOTP, passkey registration, then sign in with that passkey from identifier autofill.
 */
class IdentityFlowTest {

    static final Path ROOT = Path.of(System.getProperty("faye.repoRoot"));
    static final String ORIGIN = "http://localhost:3000";
    static final String SECRET = "JBSWY3DPEHPK3PXP";
    static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    final UserStore users = new InMemoryUserStore();
    final MockFlowHost host = new MockFlowHost();
    final FlowVersions versions = new InMemoryFlowVersions();
    final ClientPlatform browser =
            new ClientPlatform(new Origin(ORIGIN), new WebAuthnAuthenticatorAdaptor(EmulatorUtil.PACKED_AUTHENTICATOR));
    Engine engine;
    int challenges;

    @BeforeEach
    void setUp() throws Exception {
        users.save(new User(
                "user-alice",
                "alice@example.com",
                "Alice",
                true,
                Passwords.hash("right"),
                SECRET,
                -1,
                List.of(),
                false));
        users.save(new User(
                "user-bob", "bob@example.com", null, true, Passwords.hash("right"), null, -1, List.of(), false));
        versions.publish(
                Engine.DEFAULT_TENANT,
                FlowVersion.of(
                        IrCodec.readFlow(Files.readString(ROOT.resolve("conformance/flows/passkey-totp.json")))));
        var settings = new WebAuthnSettings("localhost", Set.of(ORIGIN));
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        engine = new Engine(
                versions,
                new HandlerRegistry(IdentityHandlers.create(users, settings, clock)),
                new InMemoryFlowStore(),
                host);
    }

    @Test
    void passwordAndTotpThenRegisteredPasskeySignsInFromAutofill() {
        // Password plus TOTP, then the offered passkey is created.
        ScreenView s = start(Map.of());
        assertThat(s.screen()).containsKey("passkey");
        s = screen(submit(s, Map.of("identifier", "Alice@Example.com")));
        assertThat(s.screen()).containsEntry("type", "password");
        s = screen(submit(s, Map.of("password", "right")));
        assertThat(s.screen()).containsEntry("type", "otp");
        s = screen(submit(s, Map.of("code", Totp.code(Base32.decode(SECRET), NOW.getEpochSecond() / 30))));
        assertThat(s.screen()).containsEntry("type", "passkey").containsKey("register");
        assertThat(s.step()).isEqualTo("add-passkey");
        assertThat(submit(s, Map.of("passkey", register(s)))).isInstanceOf(Redirect.class);
        // register carries the algorithms the server verifies
        assertThat(((Map<?, ?>) Objects.requireNonNull(s.screen().get("register"))).get("algorithms"))
                .isEqualTo(List.of(-7L, -257L));

        var first = host.accepted().get(0).outcome();
        assertThat(first.subject()).isEqualTo("user-alice");
        assertThat(first.amr()).containsExactly("pwd", "otp");
        assertThat(first.acr()).isEqualTo("faye:2fa");
        assertThat(users.bySubject("user-alice").orElseThrow().passkeys()).hasSize(1);

        // Next sign in: the passkey chosen from identifier autofill is the whole sign in.
        s = start(Map.of());
        assertThat(submit(s, Map.of("passkey", assertion(s, challenge(s))))).isInstanceOf(Redirect.class);
        var second = host.accepted().get(1).outcome();
        assertThat(second.subject()).isEqualTo("user-alice");
        assertThat(second.amr()).contains("hwk");
        assertThat(host.rejected()).isEmpty();
    }

    @Test
    void theJsonSimpleWebAuthnPostsVerifies() {
        // @simplewebauthn/browser posts more than our own serializer did: a top level
        // authenticatorAttachment, and on registration response.transports, response.publicKey,
        // response.publicKeyAlgorithm and response.authenticatorData (ADR 0022). register() and
        // assertion() produce that shape; this test proves webauthn4j accepts it end to end.
        signInWithPasswordTotpAndRegister();

        assertThat(users.bySubject("user-alice").orElseThrow().passkeys()).hasSize(1);
        ScreenView s = start(Map.of());
        assertThat(submit(s, Map.of("passkey", assertion(s, challenge(s))))).isInstanceOf(Redirect.class);
        assertThat(host.accepted().getLast().outcome().subject()).isEqualTo("user-alice");
    }

    @Test
    void passkeyAnsweringAnotherChallengeFails() {
        signInWithPasswordTotpAndRegister();

        ScreenView s = start(Map.of());
        String stale = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        submit(s, Map.of("passkey", assertion(s, stale)));
        assertThat(host.rejected())
                .singleElement()
                .satisfies(r -> assertThat(r.description()).isEqualTo("invalid_credentials"));
    }

    @Test
    void stepUpEnrollsTotpForAnAccountWithoutOne() {
        ScreenView s = start(Map.of("acr_values", List.of("faye:2fa")));
        s = screen(submit(s, Map.of("identifier", "bob@example.com")));
        s = screen(submit(s, Map.of("password", "right")));
        assertThat(s.screen()).containsEntry("type", "otp_enroll");
        String secret = text(s.screen(), "secret");
        assertThat((String) s.screen().get("uri"))
                .startsWith("otpauth://totp/Faye%3Abob%40example.com?secret=" + secret);

        // A wrong code keeps the same secret on the retried screen.
        s = screen(submit(s, Map.of("code", "000000")));
        assertThat(s.screen()).containsEntry("secret", secret).containsEntry("attemptsLeft", 2);
        s = screen(submit(s, Map.of("code", Totp.code(Base32.decode(secret), NOW.getEpochSecond() / 30))));
        assertThat(s.screen()).containsEntry("type", "passkey").containsKey("register");
        submit(s, Map.of("skip", true));

        var accepted = host.accepted().get(0).outcome();
        assertThat(accepted.amr()).containsExactly("pwd", "otp");
        assertThat(accepted.acr()).isEqualTo("faye:2fa");
        assertThat(users.bySubject("user-bob").orElseThrow().totpSecret()).isEqualTo(secret);
    }

    @Test
    void aTotpCodeIsAcceptedOnce() {
        String code = Totp.code(Base32.decode(SECRET), NOW.getEpochSecond() / 30);
        for (int i = 0; i < 2; i++) {
            ScreenView s = start(Map.of());
            s = screen(submit(s, Map.of("identifier", "alice@example.com")));
            s = screen(submit(s, Map.of("password", "right")));
            EngineResponse after = submit(s, Map.of("code", code));
            if (i == 1) {
                assertThat(screen(after).screen()).containsEntry("type", "otp");
                assertThat(screen(after).screen()).containsEntry("errors", List.of(Map.of("code", "invalid_code")));
            }
        }
    }

    @Test
    void unknownAccountsResolveLikePasswordOnlyAccounts() {
        var resolve = new HandlerRegistry(
                        IdentityHandlers.create(users, new WebAuthnSettings("localhost", Set.of()), Clock.systemUTC()))
                .find("identity.resolve")
                .orElseThrow();
        var env = new faye.engine.Env(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), faye.engine.Env.Auth.NONE);
        var unknown = (faye.engine.Outcome.Ok)
                resolve.invoke(env, new Submission(Map.of("identifier", "nobody@example.com")), Map.of());
        var bob = (faye.engine.Outcome.Ok)
                resolve.invoke(env, new Submission(Map.of("identifier", "bob@example.com")), Map.of());
        for (String key : List.of("has_password", "has_totp", "has_passkey", "mfa_required", "identified_by")) {
            assertThat(unknown.user().get(key)).as(key).isEqualTo(bob.user().get(key));
        }
    }

    @Test
    void anAccountOfOneTenantIsUnknownToTheClientsOfAnother() {
        users.save(new User(
                "user-carol",
                "carol@acme.example",
                "Carol",
                true,
                Passwords.hash("right"),
                null,
                -1,
                List.of(),
                false,
                false,
                "acme"));
        var resolve = new HandlerRegistry(
                        IdentityHandlers.create(users, new WebAuthnSettings("localhost", Set.of()), Clock.systemUTC()))
                .find("identity.resolve")
                .orElseThrow();
        var input = new Submission(Map.of("identifier", "carol@acme.example"));
        for (var c : List.of(Map.entry("acme", true), Map.entry("other", false), Map.entry("", false))) {
            Map<String, Object> client = c.getKey().isEmpty()
                    ? Map.of("client_id", "demo")
                    : Map.of("client_id", "app", "metadata", Map.of("tenant", c.getKey()));
            var env = new faye.engine.Env(
                    Map.of(), client, Map.of(), Map.of(), Map.of(), Map.of(), faye.engine.Env.Auth.NONE);
            var resolved = (faye.engine.Outcome.Ok) resolve.invoke(env, input, Map.of());
            assertThat(resolved.user().get("exists")).as(c.getKey()).isEqualTo(c.getValue());
        }

        // Through the engine with the default tenant's client, the right password fails as for anyone unknown
        ScreenView s = screen(submit(start(Map.of()), Map.of("identifier", "carol@acme.example")));
        s = screen(submit(s, Map.of("password", "right")));
        assertThat(s.screen()).containsEntry("errors", List.of(Map.of("code", "invalid_credentials")));
        // Accounts every tenant shares still sign in
        assertThat(users.byIdentifier("alice@example.com").orElseThrow().tenant())
                .isNull();
    }

    @Test
    void previewAccountsAreUnknownExceptToPreviewClients() {
        users.save(new User(
                "preview-1",
                "tester@preview.faye.test",
                null,
                true,
                Passwords.hash("right"),
                null,
                -1,
                List.of(),
                false,
                true));
        // A production client: the account resolves as unknown, so the right password fails
        ScreenView s = screen(submit(start(Map.of()), Map.of("identifier", "tester@preview.faye.test")));
        assertThat(s.screen()).containsEntry("type", "password");
        s = screen(submit(s, Map.of("password", "right")));
        assertThat(s.screen()).containsEntry("errors", List.of(Map.of("code", "invalid_credentials")));

        // A preview client finds it
        Previews previews = new Previews() {
            @Override
            public Optional<FlowVersion> version(String tenantId) {
                return versions.current(tenantId);
            }

            @Override
            public Map<String, Object> request(String tenantId) {
                return Map.of();
            }

            @Override
            public void trace(FlowState state, TraceEvent event) {}
        };
        engine = new Engine(
                versions,
                new HandlerRegistry(IdentityHandlers.create(
                        users, new WebAuthnSettings("localhost", Set.of(ORIGIN)), Clock.fixed(NOW, ZoneOffset.UTC))),
                new InMemoryFlowStore(),
                host,
                TenantPolicies.of(Map.of()),
                HookRunner.NONE,
                previews);
        host.register(new HostRequest(
                HostKind.LOGIN,
                "preview",
                Map.of("client_id", "preview-default", "metadata", Map.of("preview", true)),
                Map.of()));
        s = screen(engine.start(HostKind.LOGIN, "preview", Map.of()));
        s = screen(submit(s, Map.of("identifier", "tester@preview.faye.test")));
        assertThat(submit(s, Map.of("password", "right"))).isInstanceOf(Redirect.class);
        assertThat(host.accepted().getLast().outcome().subject()).isEqualTo("preview-1");
    }

    // ---- helpers ----

    void signInWithPasswordTotpAndRegister() {
        ScreenView s = start(Map.of());
        s = screen(submit(s, Map.of("identifier", "alice@example.com")));
        s = screen(submit(s, Map.of("password", "right")));
        s = screen(submit(s, Map.of("code", Totp.code(Base32.decode(SECRET), NOW.getEpochSecond() / 30))));
        submit(s, Map.of("passkey", register(s)));
    }

    ScreenView start(Map<String, Object> hydra) {
        String challenge = "c" + challenges++;
        host.register(new HostRequest(HostKind.LOGIN, challenge, Map.of("client_id", "demo"), hydra));
        return screen(engine.start(HostKind.LOGIN, challenge, Map.of()));
    }

    EngineResponse submit(ScreenView s, Map<String, Object> payload) {
        return engine.submit(s.flowId(), s.nonce(), new Submission(payload));
    }

    static ScreenView screen(EngineResponse r) {
        assertThat(r).isInstanceOf(ScreenView.class);
        return (ScreenView) r;
    }

    @SuppressWarnings("unchecked")
    static String challenge(ScreenView identifier) {
        return text(
                (Map<String, Object>) Objects.requireNonNull(identifier.screen().get("passkey")), "challenge");
    }

    /**
     * What the Login UI posts after navigator.credentials.create with the screen's options, in the
     * shape {@code @simplewebauthn/browser} 13 serializes it.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> register(ScreenView s) {
        var reg = (Map<String, Object>) Objects.requireNonNull(s.screen().get("register"));
        var options = new PublicKeyCredentialCreationOptions(
                new PublicKeyCredentialRpEntity("localhost", "Faye"),
                new PublicKeyCredentialUserEntity(
                        decode(text(reg, "userId")), text(reg, "userName"), text(reg, "displayName")),
                new DefaultChallenge(decode(text(s.screen(), "challenge"))),
                ((List<?>) Objects.requireNonNull(reg.get("algorithms")))
                        .stream()
                                .map(alg -> new PublicKeyCredentialParameters(
                                        PublicKeyCredentialType.PUBLIC_KEY,
                                        COSEAlgorithmIdentifier.create(((Number) alg).longValue())))
                                .toList(),
                null,
                List.of(),
                new AuthenticatorSelectionCriteria(
                        null, ResidentKeyRequirement.REQUIRED, UserVerificationRequirement.REQUIRED),
                null,
                null);
        PublicKeyCredential<AuthenticatorAttestationResponse, ?> credential = browser.create(options);
        var response = Objects.requireNonNull(credential.getResponse());
        Map<String, Object> responseJson = new LinkedHashMap<>();
        responseJson.put("clientDataJSON", encode(response.getClientDataJSON()));
        responseJson.put("attestationObject", encode(response.getAttestationObject()));
        responseJson.put("transports", List.of("internal"));
        // SimpleWebAuthn also sends these attestation details
        responseJson.put("authenticatorData", encode(new byte[37]));
        responseJson.put("publicKey", encode(new byte[77]));
        responseJson.put("publicKeyAlgorithm", -7);
        return credentialJson(credential.getRawId(), responseJson);
    }

    /** What the Login UI posts after navigator.credentials.get for this challenge. */
    Map<String, Object> assertion(ScreenView s, String challenge) {
        var options = new PublicKeyCredentialRequestOptions(
                new DefaultChallenge(decode(challenge)),
                null,
                "localhost",
                null,
                UserVerificationRequirement.REQUIRED,
                null);
        PublicKeyCredential<AuthenticatorAssertionResponse, ?> credential = browser.get(options);
        var response = Objects.requireNonNull(credential.getResponse());
        Map<String, Object> responseJson = new LinkedHashMap<>();
        responseJson.put("clientDataJSON", encode(response.getClientDataJSON()));
        responseJson.put("authenticatorData", encode(response.getAuthenticatorData()));
        responseJson.put("signature", encode(response.getSignature()));
        responseJson.put("userHandle", encode("user-alice".getBytes(StandardCharsets.UTF_8)));
        return credentialJson(credential.getRawId(), responseJson);
    }

    static Map<String, Object> credentialJson(@Nullable Object rawId, Map<String, Object> response) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", encode(rawId));
        out.put("rawId", encode(rawId));
        out.put("type", "public-key");
        out.put("response", response);
        out.put("authenticatorAttachment", "platform");
        out.put("clientExtensionResults", Map.of());
        return out;
    }

    /** Takes Object because webauthn4j's byte arrays carry a nullable type annotation NullAway cannot narrow. */
    static String encode(@Nullable Object bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((byte[]) Objects.requireNonNull(bytes));
    }

    static String text(Map<?, ?> map, String key) {
        return (String) Objects.requireNonNull(map.get(key), key);
    }

    static byte[] decode(String text) {
        return Base64.getUrlDecoder().decode(text);
    }
}
