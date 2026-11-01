package faye.host.hydra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import faye.engine.Authenticated;
import faye.engine.Consented;
import faye.engine.HostKind;
import faye.engine.HostParams;
import faye.engine.HostRequest;
import faye.engine.Redirect;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs the host against a fake Hydra admin API that records what it was sent. */
class HydraFlowHostTest {

    static final ObjectMapper JSON = new ObjectMapper();

    HttpServer server;
    HydraFlowHost host;
    /** "METHOD path?query" to the JSON body received, or "" for none. */
    final Map<String, String> received = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/admin/oauth2/auth/requests/", exchange -> {
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI();
            received.put(key, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String path = exchange.getRequestURI().getPath();
            String body;
            int status = 200;
            if (exchange.getRequestURI().getQuery().contains("challenge=gone")) {
                status = 410;
                body = "{\"error\":\"request_was_handled\"}";
            } else if (path.endsWith("/login")) {
                body = """
                        {"challenge":"c1","skip":false,"subject":"","request_url":"http://hydra.test/oauth2/auth?c1",
                         "requested_scope":["openid","email"],
                         "requested_access_token_audience":[],
                         "client":{"client_id":"demo","client_name":"Demo",
                           "metadata":{"tenant":"acme","first_party":true,"tier":2,"ratio":0.5,"nested":{"n":3}}},
                         "oidc_context":{"acr_values":["faye:2fa"],"login_hint":"alice@example.com"}}
                        """;
            } else if (path.endsWith("/consent")) {
                body = """
                        {"challenge":"k1","skip":false,"subject":"user-alice","request_url":"http://hydra.test/oauth2/auth?k1",
                         "login_challenge":"c1","login_session_id":"s-1",
                         "requested_scope":["openid","email"],
                         "requested_access_token_audience":["api"],"client":{"client_id":"demo"},
                         "context":{"flowId":"f_1","versionHash":"sha256:ab"}}
                        """;
            } else if (path.endsWith("/logout") && "GET".equals(exchange.getRequestMethod())) {
                body = """
                        {"challenge":"l1","subject":"user-alice","sid":"s-1",
                         "request_url":"http://hydra.test/oauth2/sessions/logout?l1",
                         "client":{"client_id":"demo"}}
                        """;
            } else {
                body = "{\"redirect_to\":\"http://hydra.test/next" + path.substring(path.lastIndexOf('/')) + "\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        host = new HydraFlowHost(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void loadsALoginRequest() {
        HostRequest req = host.load(HostKind.LOGIN, "c1");

        assertThat(req.kind()).isEqualTo(HostKind.LOGIN);
        assertThat(req.ref()).isEqualTo("c1");
        assertThat(req.client())
                .containsEntry("client_id", "demo")
                .containsEntry("client_name", "Demo")
                .containsEntry(
                        "metadata",
                        Map.of(
                                "tenant",
                                "acme",
                                "first_party",
                                true,
                                "tier",
                                2,
                                "ratio",
                                0.5,
                                "nested",
                                Map.of("n", 3)))
                .containsEntry("first_party", true);
        // Gson reads free form numbers as Double; the host maps them back to Integer or Long
        Map<?, ?> metadata =
                (Map<?, ?>) java.util.Objects.requireNonNull(req.client().get("metadata"));
        assertThat(metadata.get("tier")).isInstanceOf(Integer.class);
        assertThat(metadata.get("ratio")).isInstanceOf(Double.class);
        Map<?, ?> nested = (Map<?, ?>) java.util.Objects.requireNonNull(metadata.get("nested"));
        assertThat(nested.get("n")).isInstanceOf(Integer.class);
        assertThat(req.params().toEnv())
                .containsEntry("skip", false)
                .containsEntry("requested_scope", List.of("openid", "email"))
                .containsEntry("acr_values", List.of("faye:2fa"))
                .containsEntry("login_hint", "alice@example.com")
                .doesNotContainKey("subject");
        assertThat(received).containsKey("GET /admin/oauth2/auth/requests/login?login_challenge=c1");
    }

    @Test
    void acceptsLoginWithSubjectAmrAndContext() throws Exception {
        HostRequest req = host.load(HostKind.LOGIN, "c1");
        var redirect = host.accept(
                req,
                new Authenticated(
                        "user-alice", List.of("pwd"), "faye:1fa", Map.of("flowId", "f_1", "versionHash", "sha256:ab")));

        assertThat(((Redirect) redirect).url()).isEqualTo("http://hydra.test/next/accept");
        JsonNode sent = JSON.readTree(received.get("PUT /admin/oauth2/auth/requests/login/accept?login_challenge=c1"));
        assertThat(sent.get("subject").asText()).isEqualTo("user-alice");
        assertThat(sent.get("amr")).containsExactly(JSON.valueToTree("pwd"));
        assertThat(sent.get("acr").asText()).isEqualTo("faye:1fa");
        assertThat(sent.get("remember").asBoolean()).isFalse();
        assertThat(sent.has("remember_for")).isFalse();
        assertThat(sent.at("/context/flowId").asText()).isEqualTo("f_1");
        assertThat(sent.at("/context/versionHash").asText()).isEqualTo("sha256:ab");
    }

    /** ADR 0033: a person who asked to stay signed in has Hydra remember the login for 30 days. */
    @Test
    void acceptsLoginThePersonAskedToRemember() throws Exception {
        HostRequest req = host.load(HostKind.LOGIN, "c1");
        host.accept(req, new Authenticated("user-alice", List.of("pwd"), "faye:1fa", Map.of(), true));

        JsonNode sent = JSON.readTree(received.get("PUT /admin/oauth2/auth/requests/login/accept?login_challenge=c1"));
        assertThat(sent.get("remember").asBoolean()).isTrue();
        assertThat(sent.get("remember_for").asLong()).isEqualTo(30L * 24 * 60 * 60);
    }

    @Test
    void acceptsConsentWithGrantsAndIdTokenClaims() throws Exception {
        HostRequest req = host.load(HostKind.CONSENT, "k1");
        assertThat(req.params().toEnv())
                .containsEntry("subject", "user-alice")
                .containsEntry("context", Map.of("flowId", "f_1", "versionHash", "sha256:ab"));

        host.accept(
                req,
                new Consented(
                        "user-alice",
                        List.of("openid", "email"),
                        List.of("api"),
                        Map.of("email", "alice@example.com", "name", "Alice", "has_totp", true)));

        JsonNode sent =
                JSON.readTree(received.get("PUT /admin/oauth2/auth/requests/consent/accept?consent_challenge=k1"));
        assertThat(sent.get("grant_scope")).containsExactly(JSON.valueToTree("openid"), JSON.valueToTree("email"));
        assertThat(sent.get("grant_access_token_audience")).containsExactly(JSON.valueToTree("api"));
        // Scope email maps email; without scope profile no name, and nothing internal to the flow
        assertThat(sent.at("/session/id_token")).isEqualTo(JSON.valueToTree(Map.of("email", "alice@example.com")));
        assertThat(sent.get("remember").asBoolean()).isFalse();
    }

    @Test
    void theTenantAndPreviewFlagComeFromTheClientsMetadata() {
        HostRequest req = host.load(HostKind.LOGIN, "c1");
        assertThat(req.tenant()).isEqualTo("acme");
        assertThat(req.preview()).isFalse();
    }

    @Test
    void onlyAPreviewClientGetsTheFlowIdClaim() {
        var outcome = new Consented("user-alice", List.of("openid", "profile"), List.of(), Map.of("name", "Alice"));
        var params = HostParams.fromMap(Map.of("context", Map.of("flowId", "f_1")));
        var preview = new HostRequest(HostKind.CONSENT, "k1", null, true, Map.of(), params);
        var production = new HostRequest(HostKind.CONSENT, "k1", null, false, Map.of(), params);
        assertThat(HydraFlowHost.idTokenClaims(preview, outcome))
                .isEqualTo(Map.of("name", "Alice", "faye_flow_id", "f_1"));
        assertThat(HydraFlowHost.idTokenClaims(production, outcome)).isEqualTo(Map.of("name", "Alice"));
    }

    @Test
    void rejectsWithErrorAndDescription() throws Exception {
        HostRequest req = host.load(HostKind.LOGIN, "c1");
        var redirect = host.reject(req, "access_denied", "invalid_credentials");

        assertThat(((Redirect) redirect).url()).isEqualTo("http://hydra.test/next/reject");
        JsonNode sent = JSON.readTree(received.get("PUT /admin/oauth2/auth/requests/login/reject?login_challenge=c1"));
        assertThat(sent.get("error").asText()).isEqualTo("access_denied");
        assertThat(sent.get("error_description").asText()).isEqualTo("invalid_credentials");
    }

    @Test
    void loadsALogoutRequest() {
        HostRequest req = host.load(HostKind.LOGOUT, "l1");

        assertThat(req.kind()).isEqualTo(HostKind.LOGOUT);
        assertThat(req.params().subject()).isEqualTo("user-alice");
        assertThat(received).containsKey("GET /admin/oauth2/auth/requests/logout?logout_challenge=l1");
    }

    @Test
    void challengeIsUrlEncoded() {
        host.load(HostKind.LOGIN, "a&c");
        assertThat(received).containsKey("GET /admin/oauth2/auth/requests/login?login_challenge=a%26c");
    }

    @Test
    void hydraErrorsSurface() {
        // A challenge Hydra no longer holds is the browser's to fix: no such sign in, not a 5xx
        assertThatThrownBy(() -> host.load(HostKind.LOGIN, "gone"))
                .isInstanceOfSatisfying(
                        faye.engine.FlowException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.code())
                                .isEqualTo(faye.engine.FlowException.Code.FLOW_NOT_FOUND));
    }
}
