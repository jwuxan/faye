package faye.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import faye.engine.FlowHost;
import faye.engine.FlowStore;
import faye.engine.FlowVersions;
import faye.engine.HookStore;
import faye.engine.InMemoryFlowStore;
import faye.engine.InMemoryFlowVersions;
import faye.engine.InMemoryHookStore;
import faye.host.hydra.HydraFlowHost;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

/** With a Hydra admin URL configured, login and consent go through the Hydra admin API. */
@SpringBootTest
@AutoConfigureMockMvc
class HydraWiringTest {

    static final ObjectMapper JSON = new ObjectMapper();
    /** Accept and reject bodies received by the fake Hydra, keyed by path. */
    static final Map<String, JsonNode> SENT = new ConcurrentHashMap<>();

    static final HttpServer HYDRA = fakeHydra();

    @DynamicPropertySource
    static void hydra(DynamicPropertyRegistry registry) {
        registry.add(
                "faye.hydra-admin-url",
                () -> "http://127.0.0.1:" + HYDRA.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        HYDRA.stop(0);
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
    org.springframework.test.web.servlet.MockMvc mvc;

    @Autowired
    FlowHost host;

    @Test
    void signsInAndConsentsThroughHydra() throws Exception {
        assertThat(host).isInstanceOf(HydraFlowHost.class);

        String location = mvc.perform(get("/login").param("login_challenge", "c1"))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        String flowId = FlowApiTest.flowId(location);

        JsonNode identifier = call(get("/api/flows/" + flowId));
        assertThat(identifier.at("/screen/identifier").asText())
                .as("login_hint prefill")
                .isEqualTo("alice@example.com");
        JsonNode password = submit(flowId, identifier, Map.of("identifier", "alice@example.com"));
        JsonNode done = submit(flowId, password, Map.of("password", "correct horse battery staple"));

        assertThat(done.at("/redirectTo").asText()).isEqualTo("http://hydra.test/login/accept");
        JsonNode accepted = sent("/admin/oauth2/auth/requests/login/accept");
        assertThat(accepted.get("subject").asText()).isEqualTo("user-alice");
        assertThat(accepted.get("amr").toString()).isEqualTo("[\"pwd\"]");
        assertThat(accepted.at("/context/flowId").asText()).isEqualTo(flowId);

        String consented = mvc.perform(get("/consent").param("consent_challenge", "k1"))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        assertThat(consented).isEqualTo("http://hydra.test/consent/accept");
        assertThat(sent("/admin/oauth2/auth/requests/consent/accept")
                        .get("grant_scope")
                        .toString())
                .isEqualTo("[\"openid\",\"email\"]");
    }

    static JsonNode sent(String path) {
        JsonNode body = SENT.get(path);
        assertThat(body).as("hydra received " + path).isNotNull();
        return java.util.Objects.requireNonNull(body);
    }

    JsonNode submit(String flowId, JsonNode screen, Map<String, Object> payload) throws Exception {
        var body = Map.of("nonce", screen.get("nonce").asText(), "action", "submit", "payload", payload);
        return call(post("/api/flows/" + flowId + "/submit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    JsonNode call(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return JSON.readTree(mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    static HttpServer fakeHydra() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/admin/oauth2/auth/requests/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String body;
                if (path.endsWith("/login")) {
                    body = """
                            {"skip":false,"subject":"","requested_scope":["openid","email"],
                             "client":{"client_id":"demo","metadata":{"tenant":"default"}},
                             "oidc_context":{"login_hint":"alice@example.com"}}
                            """;
                } else if (path.endsWith("/consent")) {
                    body = """
                            {"skip":false,"subject":"user-alice","requested_scope":["openid","email"],
                             "requested_access_token_audience":[],"client":{"client_id":"demo"}}
                            """;
                } else {
                    SENT.put(path, JSON.readTree(exchange.getRequestBody()));
                    String section = path.substring("/admin/oauth2/auth/requests/".length());
                    body = "{\"redirect_to\":\"http://hydra.test/" + section + "\"}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
