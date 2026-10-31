package faye.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import faye.identity.Base32;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * M6: preview clients run the tenant's draft once it passes the checker, with the scenario's
 * request overrides and preview only accounts; their runs are traced, fed live over SSE, and
 * checked against the paths the checker predicted.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "faye.admin-token=test-token",
            "faye.preview-enabled=true",
            "faye.tenants.preview.flow=classpath:flows/password-only.yaml",
            "faye.tenants.unsafe.flow=classpath:flows/password-only.yaml",
            "faye.tenants.scenario.flow=classpath:flows/password-only.yaml",
            "faye.tenants.seeded.flow=classpath:flows/password-only.yaml",
            "faye.tenants.traced.flow=classpath:flows/password-only.yaml",
            "faye.tenants.live.flow=classpath:flows/password-only.yaml",
        })
@AutoConfigureMockMvc
class MilestoneSixTest {

    static final ObjectMapper JSON = new ObjectMapper();

    /** Signs in with a second factor only when the request comes from a documentation range IP. */
    static final String IP_STEP_UP = """
            flow: ip-step-up
            version: 1
            login:
              steps:
                identify: { screen: identifier, use: identity.resolve }
                credential: { retry: 3, screen: password, use: password.verify }
                second-factor:
                  if: { eq: [request.ip, "203.0.113.7"] }
                  retry: 3
                  screen: otp
                  use: totp.verify
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

        @Bean
        @Primary
        TraceStore inMemoryTraceStore() {
            return new InMemoryTraceStore();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    MockFlowHost host;

    @LocalServerPort
    int port;

    int challenges;

    @Test
    void previewClientRunsTheDraftAndProductionClientsTheCurrentVersion() throws Exception {
        String current = currentVersion("preview");
        admin(MilestoneFiveTest.saveDraft("preview", MilestoneThreeTest.PASSWORD_TOTP), status().isOk());

        // Production: the published password only flow
        JsonNode s = login("preview", false);
        s = submit(s, Map.of("identifier", "alice@example.com"));
        submit(s, Map.of("password", MilestoneThreeTest.PASSWORD));
        assertThat(host.accepted().getLast().outcome().amr()).containsExactly("pwd");

        // Preview: the draft, which asks for TOTP
        s = login("preview", true);
        s = submit(s, Map.of("identifier", "alice@example.com"));
        s = submit(s, Map.of("password", MilestoneThreeTest.PASSWORD));
        assertThat(s.at("/screen/type").asText()).isEqualTo("otp");

        // Nothing was published
        assertThat(currentVersion("preview")).isEqualTo(current);
        JsonNode versions = admin(get("/admin/tenants/preview/versions"), status().isOk());
        assertThat(versions.get("versions")).hasSize(1);
    }

    @Test
    void aDraftThatFailsTheCheckerIsNotRun() throws Exception {
        admin(MilestoneFiveTest.saveDraft("unsafe", """
                        flow: unsafe
                        version: 1
                        login:
                          steps:
                            identify: { screen: identifier, use: identity.resolve }
                        """), status().isOk());
        String location = start("unsafe", true);
        assertThat(location).contains("result=access_denied");
        assertThat(host.rejected().getLast().description()).isEqualTo("preview_unavailable");
    }

    @Test
    void theScenarioOverridesTheRequestOfPreviewFlowsOnly() throws Exception {
        admin(MilestoneFiveTest.saveDraft("scenario", IP_STEP_UP), status().isOk());
        admin(scenario("scenario", Map.of("hostname", "x")), status().isBadRequest());
        admin(scenario("scenario", Map.of("ip", "203.0.113.7")), status().isOk());
        assertThat(admin(get("/admin/tenants/scenario/preview"), status().isOk())
                        .at("/scenario/request/ip")
                        .asText())
                .isEqualTo("203.0.113.7");

        JsonNode s = login("scenario", true);
        s = submit(s, Map.of("identifier", "alice@example.com"));
        s = submit(s, Map.of("password", MilestoneThreeTest.PASSWORD));
        assertThat(s.at("/screen/type").asText()).isEqualTo("otp");

        admin(scenario("scenario", Map.of()), status().isOk());
        s = login("scenario", true);
        s = submit(s, Map.of("identifier", "alice@example.com"));
        submit(s, Map.of("password", MilestoneThreeTest.PASSWORD));
        assertThat(host.accepted().getLast().outcome().amr()).containsExactly("pwd");
    }

    @Test
    void seededAccountsSignInOnlyThroughPreviewClients() throws Exception {
        JsonNode seeded = admin(
                post("/admin/tenants/seeded/preview/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"totp\":true}"),
                status().isCreated());
        String identifier = seeded.get("identifier").asText();
        String password = seeded.get("password").asText();
        assertThat(identifier).endsWith("@preview.faye.test");

        JsonNode s = login("seeded", false);
        s = submit(s, Map.of("identifier", identifier));
        s = submit(s, Map.of("password", password));
        assertThat(s.at("/screen/errors/0/code").asText()).isEqualTo("invalid_credentials");

        admin(MilestoneFiveTest.saveDraft("seeded", MilestoneThreeTest.PASSWORD_TOTP), status().isOk());
        s = login("seeded", true);
        s = submit(s, Map.of("identifier", identifier));
        s = submit(s, Map.of("password", password));
        submit(s, Map.of("code", totp(seeded.get("totpSecret").asText())));
        assertThat(host.accepted().getLast().outcome().subject()).startsWith("preview-");
        assertThat(host.accepted().getLast().outcome().amr()).containsExactly("pwd", "otp");
    }

    @Test
    void aPreviewRunIsTracedAndCheckedAgainstThePredictedPaths() throws Exception {
        admin(MilestoneFiveTest.saveDraft("traced", MilestoneThreeTest.PASSWORD_TOTP), status().isOk());
        JsonNode s = login("traced", true);
        String flowId = s.get("flowId").asText();
        s = submit(s, Map.of("identifier", "alice@example.com"));
        JsonNode run = json(get("/api/preview/flows/" + flowId));
        assertThat(run.get("result").isNull()).isTrue();
        assertThat(run.get("matches").isNull()).isTrue();

        s = submit(s, Map.of("password", "wrong"));
        s = submit(s, Map.of("password", MilestoneThreeTest.PASSWORD));
        submit(s, Map.of("code", totp("JBSWY3DPEHPK3PXP")));

        run = json(get("/api/preview/flows/" + flowId));
        assertThat(run.get("tenant").asText()).isEqualTo("traced");
        assertThat(run.get("entry").asText()).isEqualTo("login");
        assertThat(run.get("draft").asBoolean()).isTrue();
        assertThat(texts(run.get("visits"))).containsExactly("identify", "credential", "second-factor");
        assertThat(run.at("/events/0/event").asText()).isEqualTo("start");
        assertThat(texts(run.get("events"), "event")).contains("retry", "end");
        assertThat(run.at("/result/succeeded").asBoolean()).isTrue();
        assertThat(texts(run.at("/result/amr"))).containsExactly("pwd", "otp");
        assertThat(run.at("/result/acr").asText()).isEqualTo("faye:2fa");
        assertThat(run.get("expected")).isNotEmpty();
        assertThat(run.at("/expected/0/acr").asText()).isEqualTo("faye:2fa");
        assertThat(run.get("matches").asBoolean()).isTrue();

        // The admin API serves the same run, for its own tenant only
        assertThat(admin(get("/admin/tenants/traced/preview/flows/" + flowId), status().isOk())
                        .get("flowId")
                        .asText())
                .isEqualTo(flowId);
        admin(get("/admin/tenants/preview/preview/flows/" + flowId), status().isNotFound());

        // The consent that follows gives the preview client the login's flow id as a claim
        String consent = "consent-" + challenges++;
        host.register(new HostRequest(
                HostKind.CONSENT,
                consent,
                Map.of("client_id", "preview-traced", "metadata", Map.of("tenant", "traced", "preview", true)),
                HostParams.fromMap(Map.of(
                        "subject",
                        "user-alice",
                        "requested_scope",
                        List.of("openid"),
                        "context",
                        Map.of("flowId", flowId)))));
        mvc.perform(get("/consent").param("consent_challenge", consent)).andExpect(status().isFound());
        assertThat(host.consented().getLast().outcome().idTokenClaims()).containsEntry("faye_flow_id", flowId);

        // Production flows leave no trace
        JsonNode production = login("traced", false);
        mvc.perform(get("/api/preview/flows/" + production.get("flowId").asText()))
                .andExpect(status().isNotFound());
    }

    @Test
    void theFeedStreamsPreviewEventsAsTheyHappen() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/admin/tenants/live/preview/events"))
                .header("Authorization", "Bearer test-token")
                .build();
        HttpResponse<Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
        assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("text/event-stream");
        List<String> data = new ArrayList<>();
        CompletableFuture<Void> read = CompletableFuture.runAsync(() -> response.body()
                .filter(line -> line.startsWith("data:"))
                .takeWhile(line -> {
                    data.add(line.substring(5));
                    return data.size() < 3;
                })
                .forEach(line -> {}));

        JsonNode s = login("live", true);
        submit(s, Map.of("identifier", "alice@example.com"));
        read.get(10, TimeUnit.SECONDS);
        response.body().close();

        JsonNode first = JSON.readTree(data.get(0));
        assertThat(first.get("flowId").asText()).isEqualTo(s.get("flowId").asText());
        assertThat(first.get("tenantId").asText()).isEqualTo("live");
        assertThat(first.at("/event/event").asText()).isEqualTo("start");
        assertThat(JSON.readTree(data.get(1)).at("/event/id").asText()).isEqualTo("identify");
        assertThat(JSON.readTree(data.get(2)).at("/event/id").asText()).isEqualTo("identifier");
    }

    // ---- helpers ----

    /** Starts a sign in for the tenant from its preview client or a production one; returns the redirect. */
    String start(String tenant, boolean preview) throws Exception {
        String challenge = "c" + challenges++;
        Map<String, Object> metadata = preview ? Map.of("tenant", tenant, "preview", true) : Map.of("tenant", tenant);
        host.register(new HostRequest(
                HostKind.LOGIN,
                challenge,
                Map.of("client_id", (preview ? "preview-" : "demo-") + tenant, "metadata", metadata),
                HostParams.fromMap(Map.of())));
        return java.util.Objects.requireNonNull(mvc.perform(get("/login").param("login_challenge", challenge))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getRedirectedUrl());
    }

    JsonNode login(String tenant, boolean preview) throws Exception {
        return json(get("/api/flows/" + FlowApiTest.flowId(start(tenant, preview))));
    }

    JsonNode submit(JsonNode screen, Map<String, Object> payload) throws Exception {
        var body = Map.of("nonce", screen.get("nonce").asText(), "action", "submit", "payload", payload);
        String res = mvc.perform(post("/api/flows/" + screen.get("flowId").asText() + "/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JSON.readTree(res);
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

    String currentVersion(String tenant) throws Exception {
        return admin(get("/admin/tenants/" + tenant + "/versions"), status().isOk())
                .get("current")
                .asText();
    }

    static MockHttpServletRequestBuilder scenario(String tenant, Map<String, String> request) throws Exception {
        return put("/admin/tenants/" + tenant + "/preview/scenario")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("request", request)));
    }

    static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get(field).asText()));
        return out;
    }

    /** RFC 6238 code for now, as an authenticator app computes it. */
    static String totp(String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(Base32.decode(secret), "HmacSHA1"));
        byte[] h = mac.doFinal(ByteBuffer.allocate(8)
                .putLong(Instant.now().getEpochSecond() / 30)
                .array());
        int o = h[h.length - 1] & 15;
        int code = (ByteBuffer.wrap(h, o, 4).getInt() & 0x7fffffff) % 1_000_000;
        return String.format("%06d", code);
    }
}
