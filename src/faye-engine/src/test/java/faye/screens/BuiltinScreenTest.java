package faye.screens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import faye.engine.CredentialIssuer;
import faye.engine.Env;
import faye.engine.ScreenRegistry;
import faye.engine.ScreenSpec;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The built in screens are exactly the protocol's, and the registry holds them to their issuers. */
class BuiltinScreenTest {

    private static CredentialIssuer issuer(String screen) {
        return new CredentialIssuer() {
            @Override
            public String screen() {
                return screen;
            }

            @Override
            public Map<String, Object> issue(Env env, Map<String, Object> opts, Map<String, Object> previous) {
                return Map.of();
            }

            @Override
            public Map<String, Object> present(Env env, Map<String, Object> issued) {
                return Map.of();
            }
        };
    }

    private static final List<CredentialIssuer> ISSUERS = List.of(
            issuer("identifier"),
            issuer("passkey"),
            issuer("otp_enroll"),
            issuer("code_sent"),
            issuer("phone_enroll"),
            issuer("qr"),
            issuer("qr_approve"));

    @Test
    void builtInScreensAreTheProtocolsScreens() throws IOException {
        JsonNode schema = new ObjectMapper()
                .readTree(Path.of(System.getProperty("faye.repoRoot"), "src/schema/screen.schema.json")
                        .toFile());
        Set<String> protocol = new TreeSet<>();
        for (JsonNode ref : schema.at("/$defs/Screen/oneOf")) {
            String def = ref.get("$ref").asText().substring("#/$defs/".length());
            protocol.add(schema.at("/$defs/" + def + "/properties/type/const").asText());
        }
        Set<String> registered = BuiltinScreen.registry(ISSUERS).all().stream()
                .map(ScreenSpec::type)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(registered).isEqualTo(protocol).contains(ScreenRegistry.METHOD_PICKER);
    }

    @Test
    void onlyCredentialScreensDeclareAnIssuer() {
        assertThat(Arrays.stream(BuiltinScreen.values())
                        .filter(ScreenSpec::issued)
                        .map(ScreenSpec::type))
                .containsExactlyInAnyOrder(
                        "identifier", "passkey", "otp_enroll", "code_sent", "phone_enroll", "qr", "qr_approve");
    }

    /** Without code delivery its screens are not registered, so a flow naming them fails the checker. */
    @Test
    void deliveredCodeScreensAreRegisteredOnlyWithTheirIssuers() {
        var registry = BuiltinScreen.registry(List.of(issuer("identifier"), issuer("passkey"), issuer("otp_enroll")));
        assertThat(registry.find("code_sent")).isEmpty();
        assertThat(registry.find("phone_enroll")).isEmpty();
        assertThat(BuiltinScreen.registry(ISSUERS).find("code_sent")).isPresent();
    }

    /** Only the screens of delivered codes take actions, each with its own input (ADR 0032). */
    @Test
    void actionsAreDeclaredAndTheirInputChecked() {
        assertThat(Arrays.stream(BuiltinScreen.values())
                        .filter(s -> !s.actions().isEmpty()))
                .containsExactlyInAnyOrder(BuiltinScreen.CODE_SENT, BuiltinScreen.PHONE_ENROLL, BuiltinScreen.QR);
        assertThat(BuiltinScreen.QR.acceptsAction("poll", Map.of(), Map.of())).isTrue();
        assertThat(BuiltinScreen.QR.needsEntry()).contains(faye.ir.Entry.Kind.APPROVE);
        assertThat(BuiltinScreen.QR_APPROVE.entries()).containsExactly(faye.ir.Entry.Kind.APPROVE);
        assertThat(BuiltinScreen.QR_APPROVE.requiresSubject(Map.of())).isTrue();
        assertThat(BuiltinScreen.CODE_SENT.acceptsAction("resend", Map.of(), Map.of()))
                .isTrue();
        assertThat(BuiltinScreen.CODE_SENT.acceptsAction("resend", Map.of("to", "x"), Map.of()))
                .isFalse();
        assertThat(BuiltinScreen.PHONE_ENROLL.acceptsAction("send", Map.of("phone", "+15551234821"), Map.of()))
                .isTrue();
        for (Object bad : List.of("15551234821", "+0555123", "+1555", "+1555123482155555", 15551234821L)) {
            assertThat(BuiltinScreen.PHONE_ENROLL.acceptsAction("send", Map.of("phone", bad), Map.of()))
                    .as(String.valueOf(bad))
                    .isFalse();
        }
        assertThat(BuiltinScreen.PHONE_ENROLL.requiresSubject(Map.of())).isTrue();
    }

    @Test
    void aScreenWithActionsAndNoIssuerIsRefused() {
        ScreenSpec acting = new ScreenSpec() {
            @Override
            public String type() {
                return "acting";
            }

            @Override
            public Set<String> actions() {
                return Set.of("go");
            }

            @Override
            public Map<String, Object> prepare(Env env, Map<String, Object> opts, Map<String, Object> previous) {
                return Map.of();
            }

            @Override
            public Map<String, Object> render(Env env, Map<String, Object> data) {
                return Map.of("type", "acting");
            }

            @Override
            public boolean accepts(faye.engine.Submission input, Map<String, Object> data) {
                return true;
            }
        };
        assertThatThrownBy(() -> new ScreenRegistry(List.of(acting), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actions");
    }

    @Test
    void aDeclaringScreenWithoutItsIssuerIsRefusedAtConstruction() {
        assertThatThrownBy(() -> BuiltinScreen.registry(List.of(issuer("identifier"), issuer("otp_enroll"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passkey");
    }

    @Test
    void anIssuerForAScreenThatDeclaresNoneIsRefused() {
        var extra = List.of(issuer("identifier"), issuer("passkey"), issuer("otp_enroll"), issuer("password"));
        assertThatThrownBy(() -> BuiltinScreen.registry(extra))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");
    }

    @Test
    void screensWithoutCredentialsNeedNoIssuer() {
        var registry = new ScreenRegistry(
                List.of(BuiltinScreen.PASSWORD, BuiltinScreen.CONSENT, BuiltinScreen.METHOD_PICKER), List.of());
        assertThat(registry.issuer("password")).isEmpty();
        assertThat(registry.issuer("consent")).isEmpty();
    }

    /** The two sides of QR code sign in take what they answer and nothing else (ADR 0032). */
    @Test
    void qrScreensAcceptTheirAnswers() {
        var none = new faye.engine.Submission(Map.of());
        assertThat(BuiltinScreen.QR.accepts(none, Map.of())).isTrue();
        assertThat(BuiltinScreen.QR.accepts(new faye.engine.Submission(Map.of("code", "1")), Map.of()))
                .isFalse();
        for (Object ok : List.of(42, 10L, 99)) {
            assertThat(BuiltinScreen.QR_APPROVE.accepts(new faye.engine.Submission(Map.of("number", ok)), Map.of()))
                    .as(String.valueOf(ok))
                    .isTrue();
        }
        for (Object bad : List.of(9, 100, 42.5, "42")) {
            assertThat(BuiltinScreen.QR_APPROVE.accepts(new faye.engine.Submission(Map.of("number", bad)), Map.of()))
                    .as(String.valueOf(bad))
                    .isFalse();
        }
        assertThat(BuiltinScreen.QR_APPROVE.accepts(new faye.engine.Submission(Map.of("number", 42, "x", 1)), Map.of()))
                .isFalse();
        var env = new Env(
                Map.of(), Map.of("client_name", "Acme Web"), Map.of(), Map.of(), Map.of(), Map.of(), Env.Auth.NONE);
        assertThat(BuiltinScreen.QR_APPROVE.render(env, Map.of())).containsEntry("client", Map.of("name", "Acme Web"));
        assertThat(BuiltinScreen.QR.render(env, Map.of())).containsOnlyKeys("type");
        assertThat(BuiltinScreen.QR.acceptsAction("poll", Map.of("x", 1), Map.of()))
                .isFalse();
        assertThat(BuiltinScreen.CONSENT.needsEntry()).isEmpty();
        assertThat(BuiltinScreen.QR.entries()).containsExactly(faye.ir.Entry.Kind.AUTHENTICATE);
        assertThat(BuiltinScreen.QR.path("sign-in")).isEqualTo("/challenge/sign-in");
    }

    /**
     * ADR 0038: {@code with: { required: true }} on adding a passkey or a phone lowers to the
     * screen option {@code required}, the IR's existing screen form; such a screen says so and
     * refuses "Not now", while one the flow does not require takes it.
     */
    @Test
    void aRequiredEnrollmentCannotBeDeclined() {
        Map<String, faye.dsl.BlockSpec> blocks = Arrays.stream(faye.handlers.BuiltinBlock.values())
                .collect(Collectors.toMap(faye.handlers.BuiltinBlock::id, b -> b));
        faye.ir.Flow flow = faye.dsl.FlowCompiler.compile(
                "tenant.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { block: identifier }
                    credential: { block: password }
                    passkey: { block: add-passkey, with: { required: true } }
                    phone: { block: add-phone, with: { required: true } }
                    optional-phone: { block: add-phone }
                """, new faye.dsl.FlowCompiler.Options(Map.of(), Set.of(), Set.of(), blocks));
        String ir = faye.ir.IrCodec.canonicalJson(flow);
        assertThat(ir)
                .contains("[\"screen\",\"passkey\",{\"register\":true,\"required\":true}]")
                .contains("[\"screen\",\"phone_enroll\",{\"required\":true}]")
                .contains("[\"screen\",\"phone_enroll\"]");

        var env = new Env(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Env.Auth.NONE);
        var skip = new faye.engine.Submission(Map.of("skip", true));
        for (BuiltinScreen screen : List.of(BuiltinScreen.PASSKEY, BuiltinScreen.PHONE_ENROLL)) {
            Map<String, Object> required = screen.prepare(env, Map.of("register", true, "required", true), Map.of());
            assertThat(screen.render(env, required)).as(screen.type()).containsEntry("required", true);
            assertThat(screen.accepts(skip, required)).as(screen.type()).isFalse();
            Map<String, Object> optional = screen.prepare(env, Map.of("register", true), Map.of());
            assertThat(screen.render(env, optional)).as(screen.type()).doesNotContainKey("required");
            assertThat(screen.accepts(skip, optional)).as(screen.type()).isTrue();
        }
        // Using a passkey, rather than creating one, has nothing to decline
        assertThat(BuiltinScreen.PASSKEY.prepare(env, Map.of("required", true), Map.of()))
                .doesNotContainKey("required");
    }
}
