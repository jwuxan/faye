package faye.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import faye.engine.FlowStore;
import faye.engine.FlowVersions;
import faye.engine.HookStore;
import faye.engine.InMemoryFlowStore;
import faye.engine.InMemoryFlowVersions;
import faye.engine.InMemoryHookStore;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * F11 (ADR 0007): the screen API and the entry points as the internet sees them, before anyone is
 * signed in (boundaries B1 and B2). Any method, path, content type, cookie and body, against a live
 * flow or none, ends in a declared status (O1): 200, 302, 400, 404, 405, 409, 410, 413 or 415,
 * never a 5xx. An error carries the {@code ErrorResponse} body or none, never an exception's text;
 * a 200 is a screen or a redirect of the screen protocol (O14); a redirect from {@code /login} is
 * an absolute http(s) URL. Runs against the mock host, so any challenge starts a flow.
 */
@SpringBootTest
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class FlowApiFuzzTest {

    static final Path ROOT = Path.of(System.getProperty("faye.repoRoot"));
    static final ObjectMapper JSON = new ObjectMapper();
    static final Set<Integer> DECLARED = Set.of(200, 302, 400, 404, 405, 406, 409, 410, 413, 415);
    static final JsonSchemaFactory SCHEMAS = JsonSchemaFactory.getInstance(
            SpecVersion.VersionFlag.V202012,
            b -> b.schemaMappers(m -> m.mapPrefix(
                    "https://faye.invalid/schema/",
                    ROOT.resolve("schema").toUri().toString())));
    static final JsonSchema SCREEN = SCHEMAS.getSchema(
            SchemaLocation.of("https://faye.invalid/schema/screen.schema.json#/$defs/ScreenResponse"));
    static final JsonSchema REDIRECT = SCHEMAS.getSchema(
            SchemaLocation.of("https://faye.invalid/schema/screen.schema.json#/$defs/RedirectResponse"));
    static final JsonSchema ERROR =
            SCHEMAS.getSchema(SchemaLocation.of("https://faye.invalid/schema/screen.schema.json#/$defs/ErrorResponse"));

    static final List<String> CONTENT_TYPES = List.of(
            "application/json",
            "application/json; charset=utf-8",
            "text/plain",
            "application/x-www-form-urlencoded",
            "multipart/form-data; boundary=x",
            "application/xml",
            "application/*+json",
            "*/*",
            "");

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

    @FuzzTest(maxDuration = "120s")
    void screenApi(FuzzedDataProvider data) throws Exception {
        // A live flow, its browser cookie and its current nonce
        String challenge = data.consumeBoolean() ? "c-" + data.consumeInt(0, 1_000_000) : data.consumeString(40);
        MockHttpServletResponse started = perform(get("/login").param("login_challenge", challenge));
        String flowId = "f_none";
        Cookie cookie = new Cookie("x", "y");
        String nonce = "n_none";
        String location = started.getRedirectedUrl();
        if (started.getStatus() == 302 && location != null && location.contains("?flow=")) {
            flowId = FlowApiTest.flowId(location);
            String set = Objects.requireNonNull(started.getHeader("Set-Cookie"));
            var parsed = java.net.HttpCookie.parse(set).get(0);
            cookie = new Cookie(parsed.getName(), parsed.getValue());
            MockHttpServletResponse view = perform(get("/api/flows/" + flowId).cookie(cookie));
            nonce = JSON.readTree(view.getContentAsString()).path("nonce").asText(nonce);
        }

        int requests = data.consumeInt(1, 4);
        for (int i = 0; i < requests; i++) {
            String id = switch (data.consumeInt(0, 3)) {
                case 0, 1 -> flowId;
                case 2 -> data.pickValue(List.of("", "..", "%2F", "%00", "f_" + "a".repeat(300), "*", "%7B%7D", "é"));
                default -> URLEncoder.encode(data.consumeString(20), StandardCharsets.UTF_8);
            };
            String path = switch (data.consumeInt(0, 5)) {
                case 0, 1 -> "/api/flows/" + id + "/submit";
                case 2 -> "/api/flows/" + id;
                case 3 ->
                    "/consent?consent_challenge=" + URLEncoder.encode(data.consumeString(20), StandardCharsets.UTF_8);
                case 4 -> "/login";
                default -> "/api/flows/" + id + "/" + URLEncoder.encode(data.consumeString(8), StandardCharsets.UTF_8);
            };
            HttpMethod method = data.pickValue(List.of(
                    HttpMethod.POST,
                    HttpMethod.POST,
                    HttpMethod.GET,
                    HttpMethod.PUT,
                    HttpMethod.DELETE,
                    HttpMethod.PATCH));
            byte[] body = switch (data.consumeInt(0, 3)) {
                case 0 -> data.consumeBytes(512);
                case 1 ->
                    ("{\"nonce\":" + JSON.writeValueAsString(nonce) + ",\"action\":\"submit\",\"payload\":"
                                    + data.consumeString(200) + "}")
                            .getBytes(StandardCharsets.UTF_8);
                case 2 ->
                    ("{\"nonce\":" + JSON.writeValueAsString(nonce) + ",\"nonce\":\"n_x\",\"action\":\"submit\","
                                    + "\"payload\":{\"identifier\":\"a@b\"}}")
                            .getBytes(StandardCharsets.UTF_8);
                default ->
                    ("{\"nonce\":" + JSON.writeValueAsString(nonce) + ",\"action\":"
                                    + JSON.writeValueAsString(data.pickValue(List.of("submit", "back", "")))
                                    + ",\"payload\":{\""
                                    + data.pickValue(List.of("identifier", "password", "code", "decision", "x"))
                                    + "\":"
                                    + data.pickValue(
                                            List.of("\"a@b\"", "null", "1", "[]", "{}", "\"123456\"", "\"allow\""))
                                    + "}}")
                            .getBytes(StandardCharsets.UTF_8);
            };
            MockHttpServletRequestBuilder req = request(method, URI.create("http://localhost" + safe(path)));
            String type = data.pickValue(CONTENT_TYPES);
            if (!type.isEmpty()) req.contentType(type);
            if (data.consumeBoolean()) req.cookie(cookie);
            req.content(body);
            check(perform(req), path);
        }
    }

    /** A path MockMvc can take: an invalid URI is a client's problem the container refuses before Faye. */
    static String safe(String path) {
        try {
            URI.create("http://localhost" + path);
            // MockMvc decodes the query itself; Tomcat refuses a bad escape with a 400 before Faye
            java.net.URLDecoder.decode(path, StandardCharsets.UTF_8);
            return path;
        } catch (IllegalArgumentException e) {
            return "/api/flows/invalid";
        }
    }

    MockHttpServletResponse perform(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req).andReturn().getResponse();
    }

    static void check(MockHttpServletResponse r, String path) throws Exception {
        int status = r.getStatus();
        String body = r.getContentAsString(StandardCharsets.UTF_8);
        assertThat(status).as(path + " -> " + body).isIn(DECLARED);
        assertThat(body).as("no internals in a response").doesNotContain("Exception", "at faye.", "at java.");
        if (status == 302) {
            URI to = URI.create(Objects.requireNonNull(r.getRedirectedUrl()));
            assertThat(to.isAbsolute()).as(to.toString()).isTrue();
            assertThat(to.getScheme()).isIn("http", "https");
        } else if (status == 200 && path.startsWith("/api/")) {
            JsonNode json = JSON.readTree(body);
            JsonSchema schema = json.has("redirectTo") ? REDIRECT : SCREEN;
            assertThat(schema.validate(json)).as(body).isEmpty();
        } else if (status >= 400 && !body.isEmpty()) {
            assertThat(ERROR.validate(JSON.readTree(body))).as(body).isEmpty();
        }
    }

    /** Security standard 1 and 3: a repeated key and an oversized body are refused. */
    @Test
    void repeatedKeysAndHugeBodiesAreRefused() throws Exception {
        MockHttpServletResponse started = perform(get("/login").param("login_challenge", "c-dup"));
        String flowId = FlowApiTest.flowId(started.getRedirectedUrl());
        var parsed = java.net.HttpCookie.parse(Objects.requireNonNull(started.getHeader("Set-Cookie")))
                .get(0);
        Cookie cookie = new Cookie(parsed.getName(), parsed.getValue());
        String nonce = JSON.readTree(
                        perform(get("/api/flows/" + flowId).cookie(cookie)).getContentAsString())
                .path("nonce")
                .asText();
        MockHttpServletResponse dup = perform(request(HttpMethod.POST, "/api/flows/" + flowId + "/submit")
                .cookie(cookie)
                .contentType("application/json")
                .content("{\"nonce\":\"n_other\",\"nonce\":\"" + nonce
                        + "\",\"action\":\"submit\",\"payload\":{\"identifier\":\"a@b\"}}"));
        assertThat(dup.getStatus()).isEqualTo(400);
        MockHttpServletResponse huge = perform(request(HttpMethod.POST, "/api/flows/" + flowId + "/submit")
                .cookie(cookie)
                .contentType("application/json")
                .content("{\"nonce\":\"" + nonce + "\",\"action\":\"submit\",\"payload\":{\"identifier\":\""
                        + "a".repeat(BodyLimit.MAX_BYTES) + "\"}}"));
        assertThat(huge.getStatus()).isEqualTo(413);
        // The flow is untouched by both
        assertThat(JSON.readTree(perform(get("/api/flows/" + flowId).cookie(cookie))
                                .getContentAsString())
                        .path("nonce")
                        .asText())
                .isEqualTo(nonce);
    }

    /** Seed finding 3: only an absolute http(s) URL with a host is ever handed to the browser. */
    @Test
    void onlySafeRedirectsReachTheBrowser() {
        assertThat(FlowController.checked("https://hydra.example/oauth2/auth?login_verifier=x"))
                .isEqualTo("https://hydra.example/oauth2/auth?login_verifier=x");
        for (String bad : List.of(
                "javascript:alert(1)",
                "JavaScript:alert(1)",
                "data:text/html,x",
                "/relative",
                "//evil.example/x",
                "https://ok.example/\r\nSet-Cookie:x",
                "https://ok.example/a b",
                "http:///nohost",
                "mock://callback")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> FlowController.checked(bad))
                    .as(bad)
                    .isInstanceOf(FlowController.BadRedirect.class);
        }
    }

    /** Seed finding 1: a null field is refused as invalid_submission, not a 5xx. */
    @Test
    void nullInAPayloadIsRefused() throws Exception {
        MockHttpServletResponse started = perform(get("/login").param("login_challenge", "c-null"));
        String flowId = FlowApiTest.flowId(started.getRedirectedUrl());
        var parsed = java.net.HttpCookie.parse(Objects.requireNonNull(started.getHeader("Set-Cookie")))
                .get(0);
        Cookie cookie = new Cookie(parsed.getName(), parsed.getValue());
        String nonce = JSON.readTree(
                        perform(get("/api/flows/" + flowId).cookie(cookie)).getContentAsString())
                .path("nonce")
                .asText();
        MockHttpServletResponse r = perform(request(HttpMethod.POST, "/api/flows/" + flowId + "/submit")
                .cookie(cookie)
                .contentType("application/json")
                .content("{\"nonce\":\"" + nonce + "\",\"action\":\"submit\",\"payload\":{\"identifier\":null}}"));
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(r.getContentAsString()).isEqualTo("{\"error\":\"invalid_submission\"}");
    }
}
