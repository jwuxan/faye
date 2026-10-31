package faye.host.hydra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import faye.engine.Authenticated;
import faye.engine.Consented;
import faye.engine.FlowHost;
import faye.engine.HostKind;
import faye.engine.HostRequest;
import faye.engine.Redirect;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * FlowHost over the Ory Hydra v2 admin API ({@code /admin/oauth2/auth/requests/...}). Login, consent
 * and logout challenges share one URL scheme: {@code {kind}?{kind}_challenge=...}, then
 * {@code {kind}/accept} or {@code {kind}/reject}.
 */
public final class HydraFlowHost implements FlowHost {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final URI adminUrl;
    private final HttpClient http;

    /** @param adminUrl Hydra admin base URL, e.g. {@code http://hydra:4445} */
    public HydraFlowHost(URI adminUrl) {
        this.adminUrl = adminUrl;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @Override
    public HostRequest load(HostKind kind, String challenge) {
        JsonNode body = send(kind, challenge, "", "GET", null);
        Map<String, Object> client = new LinkedHashMap<>();
        JsonNode c = body.path("client");
        if (c.path("client_id").isTextual()) {
            client.put("client_id", c.get("client_id").textValue());
        }
        if (!c.path("client_name").asText().isEmpty()) {
            client.put("client_name", c.get("client_name").textValue());
        }
        if (c.path("metadata").isObject()) client.put("metadata", plain(c.get("metadata")));
        // Only clients registered with metadata.first_party = true are first party.
        client.put("first_party", c.path("metadata").path("first_party").asBoolean(false));

        Map<String, Object> hydra = new LinkedHashMap<>();
        if (body.path("skip").isBoolean()) hydra.put("skip", body.get("skip").booleanValue());
        if (!body.path("subject").asText().isEmpty()) {
            hydra.put("subject", body.get("subject").textValue());
        }
        copyList(body, "requested_scope", hydra);
        copyList(body, "requested_access_token_audience", hydra);
        JsonNode oidc = body.path("oidc_context");
        copyList(oidc, "acr_values", hydra);
        copyList(oidc, "ui_locales", hydra);
        if (!oidc.path("login_hint").asText().isEmpty()) {
            hydra.put("login_hint", oidc.get("login_hint").textValue());
        }
        // Set by our login accept; links a consent to the login flow that preceded it.
        if (body.path("context").isObject()) hydra.put("context", plain(body.get("context")));
        return new HostRequest(kind, challenge, client, hydra);
    }

    @Override
    public Redirect accept(HostRequest request, Authenticated outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", outcome.subject());
        body.put("amr", outcome.amr());
        if (outcome.acr() != null) body.put("acr", outcome.acr());
        body.put("remember", false);
        body.put("context", outcome.context());
        return redirect(send(request.kind(), request.challenge(), "/accept", "PUT", body));
    }

    @Override
    public Redirect accept(HostRequest request, Consented outcome) {
        Map<String, Object> body = Map.of(
                "grant_scope", outcome.grantScope(),
                "grant_access_token_audience", outcome.grantAudience(),
                "remember", false,
                "session", Map.of("id_token", outcome.idTokenClaims()));
        return redirect(send(request.kind(), request.challenge(), "/accept", "PUT", body));
    }

    @Override
    public Redirect reject(HostRequest request, String error, String description) {
        Map<String, Object> body = Map.of("error", error, "error_description", description);
        return redirect(send(request.kind(), request.challenge(), "/reject", "PUT", body));
    }

    private JsonNode send(HostKind kind, String challenge, String action, String method, @Nullable Object body) {
        String section = kind.section();
        URI uri = adminUrl.resolve("/admin/oauth2/auth/requests/" + section + action + "?" + section + "_challenge="
                + URLEncoder.encode(challenge, StandardCharsets.UTF_8));
        HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("Accept", "application/json");
        if (body == null) {
            req.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            req.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json(body)));
        }
        HttpResponse<String> res;
        try {
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("hydra " + method + " " + uri.getPath() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "hydra " + method + " " + uri.getPath() + ": " + res.statusCode() + " " + res.body());
        }
        try {
            return MAPPER.readTree(res.body());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("hydra returned non JSON for " + uri.getPath(), e);
        }
    }

    private static Redirect redirect(JsonNode body) {
        if (!body.path("redirect_to").isTextual()) {
            throw new IllegalStateException("hydra response has no redirect_to");
        }
        return new Redirect(body.get("redirect_to").textValue());
    }

    private static void copyList(JsonNode from, String field, Map<String, Object> to) {
        if (from.path(field).isArray()) to.put(field, plain(from.get(field)));
    }

    private static Object plain(JsonNode node) {
        return MAPPER.convertValue(node, Object.class);
    }

    private static String json(Object body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
