package faye.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import faye.engine.Authenticated;
import faye.engine.Completion;
import faye.engine.Consented;
import faye.engine.Engine;
import faye.engine.FlowHost;
import faye.engine.FlowStore;
import faye.engine.FlowVersions;
import faye.engine.Handoff;
import faye.engine.HookStore;
import faye.engine.HostKind;
import faye.engine.HostParams;
import faye.engine.HostRequest;
import faye.engine.InMemoryFlowStore;
import faye.engine.InMemoryFlowVersions;
import faye.engine.InMemoryHookStore;
import faye.engine.ScreenView;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * A host that started the flow by API and takes the outcome back as data (ADR 0025): the screen API
 * answers the finished flow with {@code {result}}, valid against the screen protocol, and the
 * Hydra host's redirecting routes refuse to render a handoff.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HandoffApiTest {

    /** Hands off a one time code; the system that started the flow redeems it server to server. */
    static final class HandoffHost implements FlowHost {
        final List<Authenticated> accepted = new CopyOnWriteArrayList<>();

        @Override
        public HostRequest load(HostKind kind, String ref) {
            // A consent the host remembers: it completes at once, without a screen
            HostParams params = kind == HostKind.CONSENT
                    ? HostParams.fromMap(Map.of("subject", "user-alice", "skip", true))
                    : HostParams.NONE;
            return new HostRequest(kind, ref, null, false, Map.of("client_id", "embedded-app"), params);
        }

        @Override
        public Completion accept(HostRequest request, Authenticated outcome) {
            accepted.add(outcome);
            return new Handoff(Map.of("code", "otc-" + request.ref()));
        }

        @Override
        public Completion accept(HostRequest request, Consented outcome) {
            return new Handoff(Map.of("code", "otc-" + request.ref()));
        }

        @Override
        public Completion reject(HostRequest request, String error, String description) {
            return new Handoff(Map.of("error", description));
        }
    }

    @TestConfiguration
    static class Embedded {
        @Bean
        @Primary
        HandoffHost handoffHost() {
            return new HandoffHost();
        }

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
    Engine engine;

    @Autowired
    HandoffHost host;

    final JsonSchema protocol = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(FlowApiTest.read(FlowApiTest.ROOT.resolve("schema/screen.schema.json")));

    @Test
    void aFinishedFlowAnswersWithTheHostsResult() throws Exception {
        // The embedding host starts the flow as a library call and gives the browser the binder
        String binder = Engine.newBinder();
        var first = (ScreenView) engine.start(HostKind.LOGIN, "r-1", Map.of(), binder);
        Cookie cookie = new Cookie(FlowController.COOKIE_PREFIX + first.flowId(), binder);

        JsonNode identifier = call(get("/api/flows/" + first.flowId()).cookie(cookie));
        JsonNode password = submit(first.flowId(), cookie, identifier, Map.of("identifier", "alice@example.com"));
        assertThat(password.at("/screen/type").asText()).isEqualTo("password");

        var finished = mvc.perform(post("/api/flows/" + first.flowId() + "/submit")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FlowApiTest.JSON.writeValueAsString(Map.of(
                                "nonce",
                                password.get("nonce").asText(),
                                "action",
                                "submit",
                                "payload",
                                Map.of("password", "correct horse battery staple")))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        JsonNode done = FlowApiTest.JSON.readTree(finished.getContentAsString());
        assertThat(protocol.validate(done)).isEmpty();
        assertThat(done).isEqualTo(FlowApiTest.JSON.valueToTree(Map.of("result", Map.of("code", "otc-r-1"))));
        assertThat(finished.getHeader("Set-Cookie")).contains("Max-Age=0");
        assertThat(host.accepted).singleElement().satisfies(a -> {
            assertThat(a.subject()).isEqualTo("user-alice");
            assertThat(a.amr()).containsExactly("pwd");
        });
    }

    @Test
    void theRedirectingRoutesRefuseToRenderAHandoff() {
        // A remembered consent completes at once with the host's handoff; a browser navigation must
        // not render it (it may be a one time code)
        assertThatThrownBy(() -> mvc.perform(get("/consent").param("consent_challenge", "k-1")))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot complete a redirecting route");
    }

    JsonNode submit(String flowId, Cookie cookie, JsonNode screen, Map<String, Object> payload) throws Exception {
        var body = Map.of("nonce", screen.get("nonce").asText(), "action", "submit", "payload", payload);
        return call(post("/api/flows/" + flowId + "/submit")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(FlowApiTest.JSON.writeValueAsString(body)));
    }

    JsonNode call(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        String body = mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode json = FlowApiTest.JSON.readTree(body);
        assertThat(protocol.validate(json)).as(body).isEmpty();
        return json;
    }
}
