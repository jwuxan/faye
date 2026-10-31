package faye.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import faye.dsl.FlowCompiler;
import faye.engine.Authenticated;
import faye.engine.FlowStore;
import faye.engine.FlowVersions;
import faye.engine.HookStore;
import faye.engine.HostKind;
import faye.engine.HostRequest;
import faye.engine.InMemoryFlowStore;
import faye.engine.InMemoryFlowVersions;
import faye.engine.InMemoryHookStore;
import faye.host.mock.MockFlowHost;
import faye.ir.IrCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** M3: checked publishing, version pinning, rollback and tenant MFA policy through the admin API. */
@SpringBootTest(
        properties = {
            "faye.admin-token=test-token",
            "faye.tenants.pinning.flow=classpath:flows/password-only.yaml",
            "faye.tenants.checks.flow=classpath:flows/password-only.yaml",
            "faye.tenants.strict.flow=classpath:flows/mfa-policy.yaml",
            "faye.tenants.strict.mfa-required=true",
        })
@AutoConfigureMockMvc
class MilestoneThreeTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String PASSWORD = "correct horse battery staple";

    static final String PASSWORD_TOTP = """
            flow: password-totp
            version: 2
            login:
              steps:
                identify: { screen: identifier, use: identity.resolve }
                credential: { retry: 3, screen: password, use: password.verify }
                second-factor: { retry: 3, screen: otp, use: totp.verify }
            """;

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

    /** M3 exit: publishing a new version while a user is mid login does not affect that user. */
    @Test
    void publishingMidLoginDoesNotAffectThatUser() throws Exception {
        String original = admin(get("/admin/tenants/pinning/versions"), status().isOk())
                .get("current")
                .asText();

        JsonNode midway = login("m3-pin-1", "pinning", Map.of());
        midway = submit(midway, Map.of("identifier", "alice@example.com"));
        assertThat(midway.at("/screen/type").asText()).isEqualTo("password");

        JsonNode published = admin(publish("pinning", PASSWORD_TOTP), status().isCreated());
        String next = published.get("versionHash").asText();
        assertThat(next).isNotEqualTo(original);
        assertThat(published.get("summary").asText()).startsWith("login: 9 success paths");

        // The user who started before the publish finishes on the version they started with
        submit(midway, Map.of("password", PASSWORD));
        Authenticated pinned = accepted("m3-pin-1");
        assertThat(pinned.amr()).containsExactly("pwd");
        assertThat(pinned.context()).containsEntry("versionHash", original);

        // A new login runs the new version
        JsonNode fresh = login("m3-pin-2", "pinning", Map.of());
        fresh = submit(fresh, Map.of("identifier", "alice@example.com"));
        fresh = submit(fresh, Map.of("password", PASSWORD));
        assertThat(fresh.at("/screen/type").asText()).isEqualTo("otp");

        // Rolling back moves the pointer; the flow mid login on the new version is unaffected
        assertThat(admin(setCurrent("pinning", original), status().isOk())
                        .get("versionHash")
                        .asText())
                .isEqualTo(original);
        fresh = submit(fresh, Map.of("code", "000000"));
        assertThat(fresh.at("/screen/type").asText()).isEqualTo("otp");
        assertThat(fresh.at("/screen/errors/0/code").asText()).isEqualTo("invalid_code");

        JsonNode after = login("m3-pin-3", "pinning", Map.of());
        after = submit(after, Map.of("identifier", "alice@example.com"));
        submit(after, Map.of("password", PASSWORD));
        assertThat(accepted("m3-pin-3").context()).containsEntry("versionHash", original);

        JsonNode versions = admin(get("/admin/tenants/pinning/versions"), status().isOk());
        assertThat(versions.get("current").asText()).isEqualTo(original);
        assertThat(JSON.convertValue(versions.get("versions"), String[].class)).containsExactly(original, next);
    }

    /** M3 exit: a flow missing a credential on one branch is rejected with a pointer to the YAML line. */
    @Test
    void publishRefusesABranchWithoutACredential() throws Exception {
        String before = admin(get("/admin/tenants/checks/versions"), status().isOk())
                .get("current")
                .asText();
        String lax = """
                flow: lax
                version: 1
                login:
                  steps:
                    identify:
                      screen: identifier
                      use: identity.resolve
                    credential:
                      alternative:
                        password:
                          screen: password
                          use: password.verify
                        remembered-device:
                          if: { eq: [request.ip, "10.0.0.1"] }
                          set: { trusted: true }
                """;
        JsonNode refused = admin(publish("checks", lax), status().isUnprocessableEntity());
        assertThat(refused.get("ok").asBoolean()).isFalse();
        JsonNode error = refused.get("errors").get(0);
        assertThat(error.get("source").asText()).isEqualTo("checks.yaml:14:15");
        assertThat(error.get("line").asInt()).isEqualTo(14);
        assertThat(error.get("message").asText())
                .isEqualTo("a path succeeds without verifying a credential:"
                        + " identify > credential/remembered-device > succeed with no credential");
        assertThat(admin(get("/admin/tenants/checks/versions"), status().isOk())
                        .get("current")
                        .asText())
                .isEqualTo(before);

        JsonNode validation = admin(validate("checks", lax), status().isOk());
        assertThat(validation.get("ok").asBoolean()).isFalse();
        assertThat(validation.get("summary").asText()).startsWith("login: 2 success paths");

        JsonNode syntax = admin(validate("checks", "flow: [\n"), status().isUnprocessableEntity());
        assertThat(syntax.get("errors").get(0).get("line").asInt()).isEqualTo(2);
    }

    @Test
    void adminApiNeedsTheTokenAndAKnownTenant() throws Exception {
        mvc.perform(get("/admin/tenants/checks/versions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/tenants/checks/versions").header("Authorization", "Bearer wrong"))
                .andExpect(status().isUnauthorized());
        admin(get("/admin/tenants/nobody/versions"), status().isNotFound());
        admin(setCurrent("checks", "sha256:0000"), status().isNotFound());
    }

    @Test
    void mfaTenantPolicy() throws Exception {
        // The tenant's flow reads tenant.mfa_required to ask for a code
        JsonNode s = login("m3-mfa", "strict", Map.of());
        s = submit(s, Map.of("identifier", "alice@example.com"));
        s = submit(s, Map.of("password", PASSWORD));
        assertThat(s.at("/screen/type").asText()).isEqualTo("otp");
        submit(s, Map.of("code", MilestoneTwoTest.totp(MilestoneTwoTest.ALICE_TOTP)));
        assertThat(accepted("m3-mfa").acr()).isEqualTo("faye:2fa");

        // Publishing a one factor flow to the tenant is refused
        JsonNode refused = admin(publish("strict", read("flows/password-only.yaml")), status().isUnprocessableEntity());
        assertThat(refused.get("errors").get(0).get("message").asText())
                .startsWith("the tenant requires MFA, but a path succeeds with faye:1fa");
    }

    @Test
    void mfaPolicyFlowIsTheConformanceFlow() throws Exception {
        var compiled = FlowCompiler.compile("mfa-policy.yaml", read("flows/mfa-policy.yaml"));
        var expected = IrCodec.readFlow(
                Files.readString(Path.of(System.getProperty("faye.repoRoot"), "conformance/flows/mfa-policy.json")));
        assertThat(IrCodec.hash(compiled)).isEqualTo(IrCodec.hash(expected));
    }

    /** The server does not start with a tenant whose flow fails the checker under its settings. */
    @Test
    void startupChecksEveryTenantFlow() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(FayeApplication.class, InMemory.class)
                        .web(WebApplicationType.NONE)
                        .properties(
                                "faye.tenants.weak.flow=classpath:flows/password-only.yaml",
                                "faye.tenants.weak.mfa-required=true")
                        .run()
                        .close())
                .hasRootCauseMessage("tenant weak flow fails the checker:\npassword-only.yaml:5:1: the tenant requires"
                        + " MFA, but a path succeeds with faye:1fa: identify > credential > succeed pwd");
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
        return json(get("/api/flows/" + FlowApiTest.flowId(location)));
    }

    JsonNode submit(JsonNode screen, Map<String, Object> payload) throws Exception {
        var body = Map.of("nonce", screen.get("nonce").asText(), "action", "submit", "payload", payload);
        return json(post("/api/flows/" + screen.get("flowId").asText() + "/submit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    JsonNode json(MockHttpServletRequestBuilder request) throws Exception {
        return JSON.readTree(mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    Authenticated accepted(String challenge) {
        return host.accepted().stream()
                .filter(a -> a.request().challenge().equals(challenge))
                .findFirst()
                .orElseThrow()
                .outcome();
    }

    JsonNode admin(MockHttpServletRequestBuilder request, ResultMatcher expected) throws Exception {
        String body = mvc.perform(request.header("Authorization", "Bearer test-token"))
                .andExpect(expected)
                .andReturn()
                .getResponse()
                .getContentAsString();
        return body.isEmpty() ? JSON.createObjectNode() : JSON.readTree(body);
    }

    static MockHttpServletRequestBuilder publish(String tenant, String yaml) {
        return post("/admin/tenants/" + tenant + "/publish")
                .contentType("application/yaml")
                .content(yaml);
    }

    static MockHttpServletRequestBuilder validate(String tenant, String yaml) {
        return post("/admin/tenants/" + tenant + "/validate")
                .contentType("application/yaml")
                .content(yaml);
    }

    static MockHttpServletRequestBuilder setCurrent(String tenant, String hash) throws Exception {
        return post("/admin/tenants/" + tenant + "/current")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("versionHash", hash)));
    }

    static String read(String resource) throws Exception {
        return new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8);
    }
}
