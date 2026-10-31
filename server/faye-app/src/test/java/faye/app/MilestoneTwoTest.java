package faye.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import faye.engine.Authenticated;
import faye.engine.Base32;
import faye.engine.FlowStore;
import faye.engine.FlowVersions;
import faye.engine.HookStore;
import faye.engine.HostKind;
import faye.engine.HostRequest;
import faye.engine.InMemoryFlowStore;
import faye.engine.InMemoryFlowVersions;
import faye.engine.InMemoryHookStore;
import faye.host.mock.MockFlowHost;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * M2 exit: the three YAML flows run with the real handlers and seeded accounts, acr_values
 * faye:2fa forces step up, and the accepted amr and acr match what verified.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MilestoneTwoTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String PASSWORD = "correct horse battery staple";
    /** Alice's seeded secret in application.yaml. */
    static final String ALICE_TOTP = "JBSWY3DPEHPK3PXP";

    @TestConfiguration
    static class InMemory {
        @Bean
        @Primary
        FlowStore inMemoryFlowStore() {
            return new InMemoryFlowStore();
        }

        @Bean
        @Primary
        FlowVersions inMemoryFlowVersions() {
            return new InMemoryFlowVersions();
        }

        @Bean
        @Primary
        HookStore inMemoryHookStore() {
            return new InMemoryHookStore();
        }

        @Bean
        @Primary
        TenantConfigStore inMemoryTenantConfigStore() {
            return new InMemoryTenantConfigStore();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    MockFlowHost host;

    final JsonSchema protocol = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(FlowApiTest.read(Path.of(System.getProperty("faye.repoRoot"), "schema/screen.schema.json")));

    @Test
    void passwordOnly() throws Exception {
        JsonNode s = login("m2-pwd", "default", Map.of());
        s = submit(s, Map.of("identifier", "alice@example.com"));
        s = submit(s, Map.of("password", PASSWORD));
        assertThat(s.at("/redirectTo").asText()).contains("result=accepted");

        Authenticated a = accepted("m2-pwd");
        assertThat(a.subject()).isEqualTo("user-alice");
        assertThat(a.amr()).containsExactly("pwd");
        assertThat(a.acr()).isEqualTo("faye:1fa");
    }

    @Test
    void passwordOnlyFlowCannotSatisfyTwoFactors() throws Exception {
        JsonNode s = login("m2-pwd-2fa", "default", Map.of("acr_values", List.of("faye:2fa")));
        s = submit(s, Map.of("identifier", "alice@example.com"));
        s = submit(s, Map.of("password", PASSWORD));
        assertThat(s.at("/redirectTo").asText()).contains("result=access_denied");
        assertThat(host.rejected()).anySatisfy(r -> {
            assertThat(r.request().challenge()).isEqualTo("m2-pwd-2fa");
            assertThat(r.description()).isEqualTo("acr_not_satisfied");
        });
    }

    @Test
    void passwordPlusTotp() throws Exception {
        JsonNode s = login("m2-totp", "mfa", Map.of());
        assertThat(s.at("/screen/passkey/challenge").isTextual())
                .as("passkey autofill offered")
                .isTrue();
        s = submit(s, Map.of("identifier", "alice@example.com"));
        s = submit(s, Map.of("password", PASSWORD));
        assertThat(s.at("/screen/type").asText()).isEqualTo("otp");
        assertThat(s.at("/step").asText()).isEqualTo("second-factor");
        s = submit(s, Map.of("code", totp(ALICE_TOTP)));
        assertThat(s.at("/screen/type").asText()).isEqualTo("passkey");
        assertThat(s.at("/screen/register/userName").asText()).isEqualTo("alice@example.com");
        s = submit(s, Map.of("skip", true));

        Authenticated a = accepted("m2-totp");
        assertThat(a.amr()).containsExactly("pwd", "otp");
        assertThat(a.acr()).isEqualTo("faye:2fa");
    }

    @Test
    void stepUpEnrollsTotp() throws Exception {
        // Without acr_values, Bob has no second factor and signs in with a password.
        JsonNode s = login("m2-bob", "mfa", Map.of());
        s = submit(s, Map.of("identifier", "bob@example.com"));
        submit(s, Map.of("password", PASSWORD));
        assertThat(accepted("m2-bob").acr()).isEqualTo("faye:1fa");

        // acr_values faye:2fa makes him enroll TOTP.
        s = login("m2-bob-2fa", "mfa", Map.of("acr_values", List.of("faye:2fa")));
        s = submit(s, Map.of("identifier", "bob@example.com"));
        s = submit(s, Map.of("password", PASSWORD));
        assertThat(s.at("/screen/type").asText()).isEqualTo("otp_enroll");
        s = submit(s, Map.of("code", totp(s.at("/screen/secret").asText())));
        s = submit(s, Map.of("skip", true));

        Authenticated a = accepted("m2-bob-2fa");
        assertThat(a.subject()).isEqualTo("user-bob");
        assertThat(a.amr()).containsExactly("pwd", "otp");
        assertThat(a.acr()).isEqualTo("faye:2fa");
    }

    @Test
    void consentForThirdPartyClients() throws Exception {
        JsonNode s = login("m2-partner", "partner", Map.of());
        s = submit(s, Map.of("identifier", "alice@example.com"));
        submit(s, Map.of("password", PASSWORD));
        Authenticated login = accepted("m2-partner");

        // Third party: the consent screen, linked to the login through context.flowId.
        host.register(new HostRequest(
                HostKind.CONSENT,
                "m2-consent",
                Map.of(
                        "client_id",
                        "partner-app",
                        "client_name",
                        "Partner",
                        "first_party",
                        false,
                        "metadata",
                        Map.of("tenant", "partner")),
                Map.of(
                        "subject",
                        "user-alice",
                        "requested_scope",
                        List.of("openid", "email", "profile"),
                        "context",
                        login.context())));
        String location = mvc.perform(get("/consent").param("consent_challenge", "m2-consent"))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        s = call(get("/api/flows/" + FlowApiTest.flowId(location)));
        assertThat(s.at("/screen/type").asText()).isEqualTo("consent");
        assertThat(s.at("/screen/client/name").asText()).isEqualTo("Partner");
        submit(s, Map.of("decision", "allow"));
        assertThat(host.consented()).anySatisfy(c -> {
            assertThat(c.request().challenge()).isEqualTo("m2-consent");
            assertThat(c.outcome().idTokenClaims())
                    .isEqualTo(Map.of("email", "alice@example.com", "email_verified", true, "name", "Alice"));
        });

        // First party: no screen.
        host.register(new HostRequest(
                HostKind.CONSENT,
                "m2-consent-1p",
                Map.of("client_id", "demo", "first_party", true, "metadata", Map.of("tenant", "partner")),
                Map.of("subject", "user-alice", "requested_scope", List.of("openid"), "context", login.context())));
        String direct = mvc.perform(get("/consent").param("consent_challenge", "m2-consent-1p"))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        assertThat(direct).contains("result=accepted");
    }

    // ---- helpers ----

    JsonNode login(String challenge, String tenant, Map<String, Object> hydra) throws Exception {
        host.register(new HostRequest(
                HostKind.LOGIN, challenge, Map.of("client_id", "demo", "metadata", Map.of("tenant", tenant)), hydra));
        String location = mvc.perform(get("/login").param("login_challenge", challenge))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        return call(get("/api/flows/" + FlowApiTest.flowId(location)));
    }

    JsonNode submit(JsonNode screen, Map<String, Object> payload) throws Exception {
        var body = Map.of("nonce", screen.get("nonce").asText(), "action", "submit", "payload", payload);
        return call(post("/api/flows/" + screen.get("flowId").asText() + "/submit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    JsonNode call(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        String body = mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode json = JSON.readTree(body);
        assertThat(protocol.validate(json)).as(body).isEmpty();
        return json;
    }

    Authenticated accepted(String challenge) {
        return host.accepted().stream()
                .filter(a -> a.request().challenge().equals(challenge))
                .findFirst()
                .orElseThrow()
                .outcome();
    }

    /** RFC 6238 code for now, what an authenticator app shows. */
    static String totp(String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(Base32.decode(secret), "HmacSHA1"));
        byte[] h = mac.doFinal(ByteBuffer.allocate(8)
                .putLong(System.currentTimeMillis() / 1000 / 30)
                .array());
        int offset = h[h.length - 1] & 0xf;
        int binary = ((h[offset] & 0x7f) << 24)
                | ((h[offset + 1] & 0xff) << 16)
                | ((h[offset + 2] & 0xff) << 8)
                | (h[offset + 3] & 0xff);
        return String.format("%06d", binary % 1_000_000);
    }
}
