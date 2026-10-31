package faye.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import faye.engine.FlowStore;
import faye.engine.FlowVersions;
import faye.engine.HookStore;
import faye.engine.HostKind;
import faye.engine.HostParams;
import faye.engine.HostRequest;
import faye.engine.InMemoryFlowStore;
import faye.engine.InMemoryFlowVersions;
import faye.engine.InMemoryHookStore;
import faye.host.mock.MockFlowHost;
import faye.identity.Passwords;
import faye.identity.User;
import faye.identity.UserStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** M5: the admin API behind the Admin UI: drafts, version sources, paths, hook test runs, settings and branding. */
@SpringBootTest(
        properties = {
            "faye.admin-token=test-token",
            "faye.tenants.editor.flow=classpath:flows/password-only.yaml",
            "faye.tenants.settings.flow=classpath:flows/password-only.yaml",
            "faye.tenants.policy.flow=classpath:flows/mfa-policy.yaml",
            "faye.tenants.hooked.flow=classpath:flows/risk-totp.yaml",
            "faye.tenants.hooked.egress=ip-reputation",
        })
@AutoConfigureMockMvc
class MilestoneFiveTest {

    static final ObjectMapper JSON = new ObjectMapper();

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

    @Autowired
    UserStore userStore;

    final JsonSchema protocol = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(read(Path.of(System.getProperty("faye.repoRoot")).resolve("schema/screen.schema.json")));

    @Test
    void draftStartsFromTheCurrentVersionAndVersionsKeepTheirYaml() throws Exception {
        String configured = MilestoneThreeTest.read("flows/password-only.yaml");
        JsonNode draft = admin(get("/admin/tenants/editor/draft"), status().isOk());
        assertThat(draft.get("origin").asText()).isEqualTo("current");
        assertThat(draft.get("yaml").asText()).isEqualTo(configured);

        String next = MilestoneThreeTest.PASSWORD_TOTP;
        JsonNode saved = admin(saveDraft("editor", next), status().isOk());
        assertThat(saved.get("origin").asText()).isEqualTo("draft");
        draft = admin(get("/admin/tenants/editor/draft"), status().isOk());
        assertThat(draft.get("origin").asText()).isEqualTo("draft");
        assertThat(draft.get("yaml").asText()).isEqualTo(next);

        String original = admin(get("/admin/tenants/editor/versions"), status().isOk())
                .get("current")
                .asText();
        String published = admin(MilestoneThreeTest.publish("editor", next), status().isCreated())
                .get("versionHash")
                .asText();

        JsonNode version = admin(get("/admin/tenants/editor/versions/" + published), status().isOk());
        assertThat(version.get("yaml").asText()).isEqualTo(next);
        assertThat(version.get("current").asBoolean()).isTrue();
        assertThat(JSON.readTree(version.get("ir").asText()).get(2).asText()).isEqualTo("password-totp");

        JsonNode old = admin(get("/admin/tenants/editor/versions/" + original), status().isOk());
        assertThat(old.get("yaml").asText()).isEqualTo(configured);
        assertThat(old.get("current").asBoolean()).isFalse();

        admin(get("/admin/tenants/editor/versions/sha256:0000"), status().isNotFound());
        // A version is only readable through a tenant that published it
        admin(get("/admin/tenants/settings/versions/" + published), status().isNotFound());
    }

    @Test
    void catalogAndTenants() throws Exception {
        JsonNode catalog = admin(get("/admin/catalog"), status().isOk());
        JsonNode password = find(catalog.get("handlers"), "name", "password.verify");
        assertThat(password.get("amr").toString()).isEqualTo("[\"pwd\"]");
        assertThat(password.get("bindsSubject").asBoolean()).isTrue();
        assertThat(find(catalog.get("handlers"), "name", "identity.resolve")
                        .get("amr")
                        .size())
                .isZero();
        assertThat(find(catalog.get("screens"), "type", "identifier")
                        .get("options")
                        .toString())
                .isEqualTo("[\"passkey\"]");
        assertThat(catalog.get("screens").toString()).doesNotContain("method_picker");

        JsonNode tenants = admin(get("/admin/tenants"), status().isOk());
        assertThat(find(tenants, "id", "editor").get("current").asText()).startsWith("sha256:");
        // The catalog is not per tenant, but needs the token all the same
        mvc.perform(get("/admin/catalog")).andExpect(status().isUnauthorized());
    }

    @Test
    void usersAndClientsAreListedReadOnlyWithoutSecrets() throws Exception {
        // Each tenant's pool, as the deploy seed writes it; hashed at test time, so none is in the source
        for (String tenant : List.of("editor", "settings")) {
            userStore.save(new User(
                    "s-" + tenant,
                    "someone@" + tenant + ".example",
                    "Someone",
                    true,
                    Passwords.hash("right"),
                    null,
                    -1,
                    List.of(),
                    false,
                    false,
                    tenant));
        }
        JsonNode users = admin(get("/admin/tenants/editor/users"), status().isOk());
        JsonNode alice = find(users, "identifier", "alice@example.com");
        assertThat(alice.get("subject").asText()).isEqualTo("user-alice");
        assertThat(alice.get("tenant").isNull()).isTrue();
        assertThat(alice.get("password").asBoolean()).isTrue();
        assertThat(alice.get("totp").asBoolean()).isTrue();
        assertThat(find(users, "identifier", "bob@example.com").get("totp").asBoolean())
                .isFalse();
        // The tenant's own account; not another tenant's
        assertThat(find(users, "identifier", "someone@editor.example")
                        .get("tenant")
                        .asText())
                .isEqualTo("editor");
        assertThat(users.toString()).doesNotContain("someone@settings.example");
        // Which credentials, never their values
        assertThat(users.toString()).doesNotContain("JBSWY3DPEHPK3PXP").doesNotContain("argon2");

        // The in memory host holds no clients
        assertThat(admin(get("/admin/tenants/editor/clients"), status().isOk()).size())
                .isZero();
        admin(get("/admin/tenants/nobody/clients"), status().isNotFound());
        admin(get("/admin/tenants/nobody/users"), status().isNotFound());
        mvc.perform(get("/admin/tenants/editor/users")).andExpect(status().isUnauthorized());
        // Nothing to write: users and clients belong to their directories
        admin(post("/admin/tenants/editor/users"), status().isMethodNotAllowed());
        admin(post("/admin/tenants/editor/clients"), status().isMethodNotAllowed());
    }

    @Test
    void pathsListTheStepsAndOptionsTheyVisit() throws Exception {
        JsonNode validation = admin(
                MilestoneThreeTest.validate("editor", MilestoneThreeTest.read("flows/mfa-policy.yaml")),
                status().isOk());
        assertThat(validation.get("ok").asBoolean()).isTrue();
        List<String> visits = new ArrayList<>();
        validation.get("paths").forEach(p -> visits.add(p.get("visits").toString()));
        assertThat(visits).contains("[\"identify\",\"credential\",\"second-factor\"]");
    }

    @Test
    void draftOfAFlowWithAHookFileCarriesTheFile() throws Exception {
        JsonNode draft = admin(get("/admin/tenants/hooked/draft"), status().isOk());
        assertThat(draft.get("yaml").asText()).contains("file: risk.js");
        assertThat(draft.at("/files/risk.js").asText()).contains("export default async function");
        // The YAML as stored compiles, reading the file from next to the configured flow
        assertThat(admin(MilestoneThreeTest.validate("hooked", draft.get("yaml").asText()), status().isOk())
                        .get("ok")
                        .asBoolean())
                .isTrue();
    }

    @Test
    void hookTestRuns() throws Exception {
        JsonNode good = admin(testHook("""
                        export default function (ctx, cap) {
                          cap.log.info('ip', ctx.request.ip)
                          return { set: { risk: ctx.request.ip === '10.0.0.9' ? 'high' : 'low' } }
                        }
                        """, Map.of("request", Map.of("ip", "10.0.0.9")), null), status().isOk());
        assertThat(good.get("accepted").asBoolean()).isTrue();
        assertThat(good.get("effect").asText()).isEqualTo("set vars.risk = high");
        assertThat(good.get("logs").toString()).isEqualTo("[\"ip 10.0.0.9\"]");

        JsonNode loop = admin(testHook("export default function () { for (;;) {} }", Map.of(), null), status().isOk());
        assertThat(loop.get("accepted").asBoolean()).isFalse();
        assertThat(loop.get("error").asText()).isNotEmpty();
        assertThat(loop.get("millis").asLong()).isLessThan(2000);

        JsonNode succeed = admin(testHook("export default () => ({ succeed: true })", Map.of(), null), status().isOk());
        assertThat(succeed.get("accepted").asBoolean()).isFalse();
        assertThat(succeed.get("effect").asText()).startsWith("hook_error");

        JsonNode pickInStep =
                admin(testHook("export default () => ({ pick: 'totp' })", Map.of(), null), status().isOk());
        assertThat(pickInStep.get("accepted").asBoolean()).isFalse();
        JsonNode pick = admin(
                testHook("export default (ctx) => ({ pick: ctx.options[1] })", Map.of(), List.of("totp", "enroll")),
                status().isOk());
        assertThat(pick.get("effect").asText()).isEqualTo("pick enroll");

        // The tenant grants no egress, so the call is refused even though the hook declares it
        Map<String, Object> egress = new HashMap<>();
        egress.put("source", "export default async (ctx, cap) => cap.http.getJson('ip-reputation', {})");
        egress.put("http", List.of("ip-reputation"));
        egress.put("ctx", Map.of());
        JsonNode refused = admin(
                post("/admin/tenants/editor/hooks/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(egress)),
                status().isOk());
        assertThat(refused.get("error").asText()).contains("not granted");

        admin(
                post("/admin/tenants/editor/hooks/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"export default () => ({})\",\"timeoutMs\":99999}"),
                status().isBadRequest());
    }

    @Test
    void settingsAreCheckedAgainstTheCurrentVersion() throws Exception {
        JsonNode view = admin(get("/admin/tenants/settings/settings"), status().isOk());
        assertThat(view.get("saved").asBoolean()).isFalse();
        assertThat(view.at("/settings/mfaRequired").asBoolean()).isFalse();

        // The current flow is password only: requiring MFA, or taking password.verify away, would break it
        JsonNode refused =
                admin(saveSettings("settings", Map.of("mfaRequired", true)), status().isUnprocessableEntity());
        assertThat(refused.at("/errors/0/message").asText()).startsWith("the tenant requires MFA");
        refused = admin(
                saveSettings("settings", Map.of("handlers", List.of("identity.resolve", "totp.verify"))),
                status().isUnprocessableEntity());
        assertThat(refused.at("/errors/0/message").asText()).contains("password.verify");
        admin(saveSettings("settings", Map.of("screens", List.of("nonsense"))), status().isBadRequest());
        admin(
                saveSettings("settings", Map.of("branding", Map.of("logoUrl", "javascript:alert(1)"))),
                status().isBadRequest());
        admin(saveSettings("settings", Map.of("branding", Map.of("primaryColor", "red"))), status().isBadRequest());
        assertThat(admin(get("/admin/tenants/settings/settings"), status().isOk())
                        .get("saved")
                        .asBoolean())
                .isFalse();

        Map<String, Object> branding = Map.of(
                "name", "Acme", "logoUrl", "https://acme.example/logo.svg", "primaryColor", "#0a7cff", "radius", 4);
        view = admin(saveSettings("settings", Map.of("branding", branding)), status().isOk());
        assertThat(view.get("saved").asBoolean()).isTrue();
        assertThat(view.at("/settings/branding/name").asText()).isEqualTo("Acme");

        // Screens carry the branding, within the screen protocol
        JsonNode screen = login("m5-brand", "settings");
        assertThat(screen.at("/branding/name").asText()).isEqualTo("Acme");
        assertThat(screen.at("/branding/radius").asInt()).isEqualTo(4);
        assertThat(protocol.validate(screen)).isEmpty();
        // A tenant without branding gets none
        assertThat(login("m5-plain", "editor").has("branding")).isFalse();
    }

    /**
     * A saved setting applies to the next flow that starts, not to one in progress. Bob has no TOTP,
     * so only the policy sends him to the code screen.
     */
    @Test
    void savedMfaPolicyAppliesToNewFlows() throws Exception {
        JsonNode before = login("m5-policy-1", "policy");
        before = submit(before, Map.of("identifier", "bob@example.com"));

        admin(saveSettings("policy", Map.of("mfaRequired", true)), status().isOk());

        JsonNode after = login("m5-policy-2", "policy");
        after = submit(after, Map.of("identifier", "bob@example.com"));
        after = submit(after, Map.of("password", MilestoneThreeTest.PASSWORD));
        assertThat(after.at("/screen/type").asText()).isEqualTo("otp");

        // The flow that started first finishes with one factor, under the settings it started with
        submit(before, Map.of("password", MilestoneThreeTest.PASSWORD));
        assertThat(host.accepted().stream()
                        .filter(a -> a.request().ref().equals("m5-policy-1"))
                        .findFirst()
                        .orElseThrow()
                        .outcome()
                        .acr())
                .isEqualTo("faye:1fa");
    }

    // ---- helpers ----

    static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (n.get(field).asText().equals(value)) return n;
        }
        throw new AssertionError("no " + field + " " + value + " in " + array);
    }

    JsonNode login(String challenge, String tenant) throws Exception {
        host.register(new HostRequest(
                HostKind.LOGIN,
                challenge,
                Map.of("client_id", "demo", "metadata", Map.of("tenant", tenant)),
                HostParams.fromMap(Map.of())));
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

    JsonNode admin(MockHttpServletRequestBuilder request, ResultMatcher expected) throws Exception {
        String body = mvc.perform(request.header("Authorization", "Bearer test-token"))
                .andExpect(expected)
                .andReturn()
                .getResponse()
                .getContentAsString();
        return body.isEmpty() ? JSON.createObjectNode() : JSON.readTree(body);
    }

    static MockHttpServletRequestBuilder saveDraft(String tenant, String yaml) throws Exception {
        return put("/admin/tenants/" + tenant + "/draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("yaml", yaml)));
    }

    static MockHttpServletRequestBuilder saveSettings(String tenant, Map<String, Object> settings) throws Exception {
        return put("/admin/tenants/" + tenant + "/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(settings));
    }

    static MockHttpServletRequestBuilder testHook(
            String source, Map<String, Object> ctx, @Nullable List<String> options) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("source", source);
        body.put("ctx", ctx);
        if (options != null) body.put("options", options);
        return post("/admin/tenants/editor/hooks/test")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body));
    }

    static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
