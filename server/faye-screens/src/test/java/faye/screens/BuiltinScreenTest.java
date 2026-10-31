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

    private static final List<CredentialIssuer> ISSUERS =
            List.of(issuer("identifier"), issuer("passkey"), issuer("otp_enroll"));

    @Test
    void builtInScreensAreTheProtocolsScreens() throws IOException {
        JsonNode schema = new ObjectMapper()
                .readTree(Path.of(System.getProperty("faye.repoRoot"), "schema/screen.schema.json")
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
                .containsExactlyInAnyOrder("identifier", "passkey", "otp_enroll");
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
}
