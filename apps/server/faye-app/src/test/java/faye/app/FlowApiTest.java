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
import faye.dsl.FlowCompiler;
import faye.engine.InMemoryFlowStore;
import faye.engine.InMemoryFlowVersions;
import faye.engine.InMemoryHookStore;
import faye.engine.spi.FlowStore;
import faye.engine.spi.FlowVersions;
import faye.engine.spi.HookStore;
import faye.engine.spi.HostKind;
import faye.engine.spi.HostParams;
import faye.engine.testing.MockFlowHost;
import faye.ir.IrCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

@SpringBootTest
@AutoConfigureMockMvc
class FlowApiTest {

    static final Path ROOT = Path.of(System.getProperty("faye.repoRoot"));
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

    /** What the app derives from its catalogs for the compiler. */
    @Autowired
    FlowCompiler.Options dsl;

    final JsonSchema protocol = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(read(ROOT.resolve("src/schema/screen.schema.json")));

    /** Each tenant flow in YAML is exactly the conformance flow of the same name, so the vectors cover it. */
    @ParameterizedTest
    @ValueSource(strings = {"password-only", "passkey-totp", "partner-consent"})
    void tenantFlowsAreConformanceFlows(String name) throws Exception {
        var yaml = new ClassPathResource("flows/" + name + ".yaml");
        var compiled = FlowCompiler.compile(
                name + ".yaml", yaml.getContentAsString(java.nio.charset.StandardCharsets.UTF_8), dsl);
        var expected = IrCodec.readFlow(Files.readString(ROOT.resolve("src/conformance/flows/" + name + ".json")));
        assertThat(IrCodec.hash(compiled)).isEqualTo(IrCodec.hash(expected));
    }

    @Test
    void signsInThroughTheScreenProtocol() throws Exception {
        var started = mvc.perform(get("/login").param("login_challenge", "c-api"))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse();
        String location = started.getRedirectedUrl();
        assertThat(location).startsWith("http://localhost:3000/login?flow=f_");
        String flowId = flowId(location);
        String binder = java.net.HttpCookie.parse(java.util.Objects.requireNonNull(started.getHeader("Set-Cookie")))
                .get(0)
                .getValue();

        JsonNode identifier = call(get("/api/flows/" + flowId), status().isOk());
        assertThat(identifier.at("/screen/type").asText()).isEqualTo("identifier");
        assertThat(identifier.at("/step").asText()).isEqualTo("identify");
        assertThat(identifier.at("/path").asText()).isEqualTo("/login");

        JsonNode password = submit(flowId, identifier, Map.of("identifier", "Alice@Example.com "), status().isOk());
        assertThat(password.at("/screen/type").asText()).isEqualTo("password");
        assertThat(password.at("/screen/identifier").asText()).isEqualTo("alice@example.com");
        assertThat(password.at("/path").asText()).startsWith("/challenge/");

        JsonNode stale = submit(flowId, identifier, Map.of("identifier", "x"), status().isConflict());
        assertThat(stale.at("/error").asText()).isEqualTo("stale_nonce");

        var finished = mvc.perform(post("/api/flows/" + flowId + "/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "nonce",
                                password.get("nonce").asText(),
                                "action",
                                "submit",
                                "payload",
                                Map.of("password", "correct horse battery staple")))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        // A finished flow expires its cookie
        assertThat(finished.getHeader("Set-Cookie"))
                .startsWith(FlowController.COOKIE_PREFIX + flowId + "=;")
                .contains("Max-Age=0");
        JsonNode done = JSON.readTree(finished.getContentAsString());
        assertThat(done.at("/redirectTo").asText()).contains("challenge=c-api", "result=accepted");
        // Only a failed sign in carries failed
        assertThat(done.has("failed")).isFalse();
        assertThat(host.accepted()).anySatisfy(a -> {
            assertThat(a.request().ref()).isEqualTo("c-api");
            assertThat(a.outcome().subject()).isEqualTo("user-alice");
            assertThat(a.outcome().amr()).containsExactly("pwd");
        });

        // The browser dropped the expired cookie, so the finished flow is no longer its own
        assertThat(call(get("/api/flows/" + flowId), status().isNotFound())
                        .at("/error")
                        .asText())
                .isEqualTo("flow_not_found");
        // A browser still holding the binder learns the flow finished
        assertThat(call(
                                get("/api/flows/" + flowId)
                                        .cookie(new jakarta.servlet.http.Cookie(
                                                FlowController.COOKIE_PREFIX + flowId, binder)),
                                status().isGone())
                        .at("/error")
                        .asText())
                .isEqualTo("flow_finished");
    }

    @Test
    void unknownUserGetsThePasswordScreenAndTheSameFailure() throws Exception {
        String location = mvc.perform(get("/login").param("login_challenge", "c-unknown"))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        String flowId = flowId(location);

        JsonNode identifier = call(get("/api/flows/" + flowId), status().isOk());
        JsonNode password = submit(flowId, identifier, Map.of("identifier", "nobody@example.com"), status().isOk());
        assertThat(password.at("/screen/type").asText()).isEqualTo("password");

        // Same error and attempts as a known account with a wrong password, then the same failure.
        JsonNode again = submit(flowId, password, Map.of("password", "guess"), status().isOk());
        assertThat(again.at("/screen/errors/0/code").asText()).isEqualTo("invalid_credentials");
        assertThat(again.at("/screen/attemptsLeft").asInt()).isEqualTo(2);
        again = submit(flowId, again, Map.of("password", "guess"), status().isOk());
        JsonNode done = submit(flowId, again, Map.of("password", "guess"), status().isOk());
        assertThat(done.at("/redirectTo").asText()).contains("result=access_denied");
        // The Login UI shows /error for it rather than following the redirect (ADR 0031)
        assertThat(done.at("/failed").asBoolean()).isTrue();
        assertThat(host.rejected()).anySatisfy(r -> {
            assertThat(r.request().ref()).isEqualTo("c-unknown");
            assertThat(r.description()).isEqualTo("invalid_credentials");
        });
    }

    @Test
    void consentIsAutoAcceptedWithoutAConsentSection() throws Exception {
        host.register(MockFlowHost.request(
                HostKind.CONSENT,
                "k-api",
                Map.of("client_id", "demo"),
                HostParams.fromMap(Map.of("subject", "user-alice", "requested_scope", List.of("openid")))));
        String location = mvc.perform(get("/consent").param("consent_challenge", "k-api"))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        assertThat(location).contains("challenge=k-api", "result=accepted");
        assertThat(host.consented())
                .anySatisfy(a -> assertThat(a.request().ref()).isEqualTo("k-api"));
    }

    @Test
    void refusalsUseTheErrorShape() throws Exception {
        assertThat(call(get("/api/flows/f_nope"), status().isNotFound())
                        .at("/error")
                        .asText())
                .isEqualTo("flow_not_found");

        String location = mvc.perform(get("/login").param("login_challenge", "c-bad"))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        String flowId = flowId(location);
        JsonNode identifier = call(get("/api/flows/" + flowId), status().isOk());
        assertThat(submit(flowId, identifier, Map.of("password", "x"), status().isBadRequest())
                        .at("/error")
                        .asText())
                .isEqualTo("invalid_submission");
    }

    /**
     * ADR 0024: {@code /login} binds the flow to the browser with an HttpOnly, Lax cookie, and
     * reading or answering the flow without that cookie's value is answered as for an unknown flow.
     */
    @Test
    void aFlowNeedsTheBinderOfTheBrowserThatStartedIt() throws Exception {
        var response = mvc.perform(get("/login").param("login_challenge", "c-bound"))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse();
        String flowId = flowId(response.getRedirectedUrl());
        String cookie = java.util.Objects.requireNonNull(response.getHeader("Set-Cookie"));
        assertThat(cookie)
                .startsWith(FlowController.COOKIE_PREFIX + flowId + "=")
                .contains("HttpOnly", "SameSite=Lax", "Path=/", "Max-Age=")
                // Tests reach the server as localhost
                .doesNotContain("Secure");

        var other = new jakarta.servlet.http.Cookie("other", "x");
        var wrong = new jakarta.servlet.http.Cookie(FlowController.COOKIE_PREFIX + flowId, "not-the-binder");
        for (var c : List.of(other, wrong)) {
            assertThat(call(get("/api/flows/" + flowId).cookie(c), status().isNotFound())
                            .at("/error")
                            .asText())
                    .isEqualTo("flow_not_found");
            var body = Map.of("nonce", "n_x", "action", "submit", "payload", Map.of("identifier", "a@example.com"));
            assertThat(call(
                                    post("/api/flows/" + flowId + "/submit")
                                            .cookie(c)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(JSON.writeValueAsString(body)),
                                    status().isNotFound())
                            .at("/error")
                            .asText())
                    .isEqualTo("flow_not_found");
        }
        // The browser that started it, which holds the cookie, gets the screen
        assertThat(call(get("/api/flows/" + flowId), status().isOk())
                        .at("/screen/type")
                        .asText())
                .isEqualTo("identifier");
    }

    /** The flow id in the query of the Login UI URL that {@code /login} redirects to (ADR 0029). */
    static String flowId(@Nullable String location) {
        String url = java.util.Objects.requireNonNull(location, "no redirect");
        return url.substring(url.lastIndexOf("?flow=") + "?flow=".length());
    }

    JsonNode submit(String flowId, JsonNode screen, Map<String, Object> payload, ResultMatcher expected)
            throws Exception {
        var body = Map.of("nonce", screen.get("nonce").asText(), "action", "submit", "payload", payload);
        return call(
                post("/api/flows/" + flowId + "/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)),
                expected);
    }

    JsonNode call(org.springframework.test.web.servlet.RequestBuilder request, ResultMatcher expected)
            throws Exception {
        String body = mvc.perform(request)
                .andExpect(expected)
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode json = JSON.readTree(body);
        assertThat(protocol.validate(json)).as(body).isEmpty();
        return json;
    }

    static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}
