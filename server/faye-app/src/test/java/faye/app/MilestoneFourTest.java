package faye.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * M4 exit: a risk hook routes high risk sign ins to TOTP, and malicious hooks (an infinite loop, a
 * hook returning {@code { succeed: true }}, a hook reading {@code auth}) fail safely, with the real
 * QuickJS sandbox and an HTTP egress target.
 */
@SpringBootTest(
        properties = {
            "faye.admin-token=test-token",
            "faye.tenants.hostile.flow=classpath:flows/password-only.yaml",
            "faye.tenants.hostile.egress=ip-reputation",
        })
@AutoConfigureMockMvc
class MilestoneFourTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String PASSWORD = "correct horse battery staple";

    /** The ip-reputation stand in: score 90 for a user agent containing "suspicious", else 10. */
    static final HttpServer REPUTATION = reputation();

    static final List<String> QUERIES = new CopyOnWriteArrayList<>();

    static HttpServer reputation() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/score", exchange -> {
                String query = String.valueOf(exchange.getRequestURI().getQuery());
                QUERIES.add(query);
                byte[] body = ("{\"score\":" + (query.contains("suspicious") ? 90 : 10) + "}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void egress(DynamicPropertyRegistry registry) {
        registry.add(
                "faye.egress.ip-reputation",
                () -> "http://127.0.0.1:" + REPUTATION.getAddress().getPort() + "/score");
    }

    @AfterAll
    static void stop() {
        REPUTATION.stop(0);
    }

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

    /** M4 exit: the shipped risk flow asks for TOTP only when the reputation service scores high. */
    @Test
    void riskHookRoutesHighRiskSignInsToTotp() throws Exception {
        JsonNode s = login("m4-low", "risk", "Mozilla/5.0");
        s = submit(s, Map.of("identifier", "alice@example.com"));
        s = submit(s, Map.of("password", PASSWORD));
        assertThat(accepted("m4-low").amr()).containsExactly("pwd");
        assertThat(QUERIES).anyMatch(q -> q.equals("ip=127.0.0.1&user_agent=Mozilla/5.0"));

        s = login("m4-high", "risk", "suspicious-bot/1.0");
        s = submit(s, Map.of("identifier", "alice@example.com"));
        s = submit(s, Map.of("password", PASSWORD));
        assertThat(s.at("/screen/type").asText()).isEqualTo("otp");
        assertThat(s.get("step").asText()).isEqualTo("second-factor");
        submit(s, Map.of("code", MilestoneTwoTest.totp(MilestoneTwoTest.ALICE_TOTP)));
        Authenticated high = accepted("m4-high");
        assertThat(high.amr()).containsExactly("pwd", "otp");
        assertThat(high.acr()).isEqualTo("faye:2fa");
    }

    @Test
    void infiniteLoopFailsTheSignInAtTheDeadline() throws Exception {
        publishHook("loop", "export default function () { for (;;) {} }");
        long start = System.nanoTime();
        JsonNode s = login("m4-loop", "hostile", "test");
        rejected(submit(s, Map.of("identifier", "alice@example.com")), "m4-loop", "hook_error");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void hookReturningSucceedCannotSignAnyoneIn() throws Exception {
        publishHook("succeed", "export default () => ({ succeed: true, subject: 'user-alice', amr: ['pwd'] })");
        JsonNode s = login("m4-succeed", "hostile", "test");
        rejected(submit(s, Map.of("identifier", "alice@example.com")), "m4-succeed", "hook_error");
    }

    /** {@code auth} is not in a hook's ctx, and a hook cannot write it: a var named auth is just a var. */
    @Test
    void hookCannotReadOrWriteAuth() throws Exception {
        publishHook("auth", """
                export default (ctx) => ({ set: { auth: typeof ctx.auth, subject: 'user-alice' } })
                """, """
                    leaked:
                      if: { ne: [vars.auth, "undefined"] }
                      fail: leaked
                """);
        JsonNode s = login("m4-auth", "hostile", "test");
        s = submit(s, Map.of("identifier", "alice@example.com"));
        // Still asks for the password: vars.subject proves nothing
        assertThat(s.at("/screen/type").asText()).isEqualTo("password");
        submit(s, Map.of("password", PASSWORD));
        assertThat(accepted("m4-auth").amr()).containsExactly("pwd");
    }

    @Test
    void egressTheTenantDidNotGrantIsRefusedAtPublish() throws Exception {
        String yaml = """
                flow: sneaky
                version: 1
                hooks:
                  peek:
                    source: "export default () => ({})"
                    capabilities: { http: [internal-admin] }
                login:
                  steps:
                    identify: { screen: identifier, use: identity.resolve }
                    peek: { hook: peek }
                    credential: { screen: password, use: password.verify }
                """;
        JsonNode refused = admin(MilestoneThreeTest.publish("hostile", yaml), status().isUnprocessableEntity());
        assertThat(refused.at("/errors/0/message").asText())
                .isEqualTo("hook 'peek' calls egress target 'internal-admin', which is not granted to this tenant");
    }

    // ---- helpers ----

    /** Publishes a password flow to tenant hostile that runs {@code source} as a hook after identify. */
    void publishHook(String id, String source, String... extraSteps) throws Exception {
        String yaml = """
                flow: hostile-%s
                version: 1
                hooks:
                  %s:
                    source: |
                %s
                login:
                  steps:
                    identify: { screen: identifier, use: identity.resolve }
                    run-hook: { hook: %s }
                %s
                    credential: { screen: password, use: password.verify }
                """.formatted(id, id, source.strip().indent(6).stripTrailing(), id, String.join("", extraSteps));
        admin(MilestoneThreeTest.publish("hostile", yaml), status().isCreated());
    }

    JsonNode login(String challenge, String tenant, String userAgent) throws Exception {
        host.register(new HostRequest(
                HostKind.LOGIN,
                challenge,
                Map.of("client_id", "demo", "metadata", Map.of("tenant", tenant)),
                Map.of()));
        String location = mvc.perform(
                        get("/login").param("login_challenge", challenge).header("User-Agent", userAgent))
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

    void rejected(JsonNode response, String challenge, String reason) {
        assertThat(response.has("redirectTo")).isTrue();
        assertThat(host.accepted()).noneMatch(a -> a.request().challenge().equals(challenge));
        assertThat(host.rejected()).anySatisfy(r -> {
            assertThat(r.request().challenge()).isEqualTo(challenge);
            assertThat(r.description()).isEqualTo(reason);
        });
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
}
