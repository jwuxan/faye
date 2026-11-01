package faye.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import faye.engine.InMemoryFlowStore;
import faye.engine.InMemoryFlowVersions;
import faye.engine.spi.FlowStore;
import faye.engine.spi.FlowVersions;
import faye.engine.spi.HostKind;
import faye.engine.spi.HostParams;
import faye.engine.testing.MockFlowHost;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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

/**
 * ADR 0035: flow settings per entry kind, saved over the admin API within {@code faye.flow-ttl}, and
 * a flow past its limit answered as expired.
 */
@SpringBootTest(
        properties = {
            "faye.admin-token=test-token",
            "faye.flow-ttl=30m",
            "faye.tenants.limits.flow=classpath:flows/password-only.yaml",
            "faye.tenants.expiry.flow=classpath:flows/password-only.yaml",
        })
@AutoConfigureMockMvc
class FlowSettingsTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @TestConfiguration
    static class Config {
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
        TenantConfigStore inMemoryTenantConfigStore() {
            return new InMemoryTenantConfigStore();
        }

        @Bean
        SteppedClock clock() {
            return new SteppedClock();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    MockFlowHost host;

    @Autowired
    SteppedClock clock;

    final JsonSchema protocol = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(MilestoneFiveTest.read(
                    Path.of(System.getProperty("faye.repoRoot")).resolve("src/schema/screen.schema.json")));

    @Test
    void theSettingsViewNamesEveryEntryKindAndTheDeploymentsMaximum() throws Exception {
        JsonNode view = admin(get("/admin/tenants/limits/settings"), status().isOk());
        assertThat(view.at("/flowLimits/kinds").toString()).isEqualTo("[\"authenticate\",\"consent\",\"approve\"]");
        assertThat(view.at("/flowLimits/maxSeconds").asLong()).isEqualTo(1800);
        assertThat(view.at("/flowLimits/minSeconds").asInt()).isEqualTo(60);
        assertThat(view.at("/settings/flows").isMissingNode()).isTrue();
    }

    @Test
    void settingsOutsideTheLimitsAreRefused() throws Exception {
        for (Map<String, Object> flows : List.<Map<String, Object>>of(
                Map.of("authenticate", Map.of("lifetimeSeconds", 1801)),
                Map.of("authenticate", Map.of("lifetimeSeconds", 59)),
                Map.of("consent", Map.of("idleTimeoutSeconds", 30)),
                Map.of("authenticate", Map.of("lifetimeSeconds", 600, "idleTimeoutSeconds", 601)),
                Map.of("consent", Map.of("idleTimeoutSeconds", 1801)),
                Map.of("register", Map.of("lifetimeSeconds", 600)))) {
            JsonNode refused = admin(saveFlows("limits", flows), status().isBadRequest());
            assertThat(refused.get("error").asText()).as(flows.toString()).isNotBlank();
        }

        Map<String, Object> flows = Map.of(
                "authenticate", Map.of("lifetimeSeconds", 600, "idleTimeoutSeconds", 300),
                "consent", Map.of("lifetimeSeconds", 1800));
        JsonNode saved = admin(saveFlows("limits", flows), status().isOk());
        assertThat(saved.at("/settings/flows")).isEqualTo(JSON.valueToTree(flows));
        // A kind with neither limit set is the same as one not listed
        saved = admin(saveFlows("limits", Map.of("authenticate", Map.of())), status().isOk());
        assertThat(saved.at("/settings/flows").isMissingNode()).isTrue();
    }

    @Test
    void aFlowPastItsLimitIsGoneAndAFlowStartedBeforeTheSettingsKeepsNone() throws Exception {
        String before = login("limits-before");
        admin(saveFlows("expiry", Map.of("authenticate", Map.of("lifetimeSeconds", 600))), status().isOk());
        String after = login("limits-after");

        clock.advance(Duration.ofSeconds(599));
        mvc.perform(get("/api/flows/" + after)).andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(1));
        String body = mvc.perform(get("/api/flows/" + after))
                .andExpect(status().isGone())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode error = JSON.readTree(body);
        assertThat(error.get("error").asText()).isEqualTo("flow_expired");
        assertThat(protocol.validate(error)).isEmpty();

        // The settings apply to flows that start after they are saved
        mvc.perform(get("/api/flows/" + before)).andExpect(status().isOk());
    }

    String login(String challenge) throws Exception {
        host.register(MockFlowHost.request(
                HostKind.LOGIN,
                challenge,
                Map.of("client_id", "demo", "metadata", Map.of("tenant", "expiry")),
                HostParams.fromMap(Map.of())));
        String location = mvc.perform(get("/login").param("login_challenge", challenge))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        return FlowApiTest.flowId(location);
    }

    JsonNode admin(MockHttpServletRequestBuilder request, ResultMatcher expected) throws Exception {
        String body = mvc.perform(request.header("Authorization", "Bearer test-token"))
                .andExpect(expected)
                .andReturn()
                .getResponse()
                .getContentAsString();
        return body.isEmpty() ? JSON.createObjectNode() : JSON.readTree(body);
    }

    static MockHttpServletRequestBuilder saveFlows(String tenant, Map<String, Object> flows) throws Exception {
        return put("/admin/tenants/" + tenant + "/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("flows", flows)));
    }

    /** A clock that moves only when the test says so. */
    static final class SteppedClock extends Clock {
        private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
