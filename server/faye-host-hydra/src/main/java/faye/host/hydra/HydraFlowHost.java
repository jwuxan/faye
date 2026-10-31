package faye.host.hydra;

import faye.engine.Authenticated;
import faye.engine.Consented;
import faye.engine.FlowHost;
import faye.engine.HostKind;
import faye.engine.HostRequest;
import faye.engine.Redirect;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import sh.ory.hydra.ApiClient;
import sh.ory.hydra.ApiException;
import sh.ory.hydra.api.OAuth2Api;
import sh.ory.hydra.model.AcceptOAuth2ConsentRequest;
import sh.ory.hydra.model.AcceptOAuth2ConsentRequestSession;
import sh.ory.hydra.model.AcceptOAuth2LoginRequest;
import sh.ory.hydra.model.OAuth2Client;
import sh.ory.hydra.model.OAuth2ConsentRequestOpenIDConnectContext;
import sh.ory.hydra.model.OAuth2RedirectTo;
import sh.ory.hydra.model.RejectOAuth2Request;

/**
 * FlowHost over the Ory Hydra v2 admin API ({@code /admin/oauth2/auth/requests/...}), through Ory's
 * generated Java client (ADR 0021). Generated getters are unannotated, so every value used is null
 * checked.
 */
public final class HydraFlowHost implements FlowHost {

    private static final int TIMEOUT_MS = 10_000;

    private final OAuth2Api api;

    /** @param adminUrl Hydra admin base URL, e.g. {@code http://hydra:4445} */
    public HydraFlowHost(URI adminUrl) {
        ApiClient client = new ApiClient();
        client.setBasePath(adminUrl.toString());
        client.setConnectTimeout(TIMEOUT_MS);
        client.setReadTimeout(TIMEOUT_MS);
        client.setWriteTimeout(TIMEOUT_MS);
        this.api = new OAuth2Api(client);
    }

    @Override
    public HostRequest load(HostKind kind, String challenge) {
        try {
            Map<String, Object> hydra = new LinkedHashMap<>();
            OAuth2Client client;
            switch (kind) {
                case LOGIN -> {
                    var r = api.getOAuth2LoginRequest(challenge);
                    client = r.getClient();
                    put(r.getSkip(), "skip", hydra);
                    put(r.getSubject(), "subject", hydra);
                    put(r.getRequestedScope(), "requested_scope", hydra);
                    put(r.getRequestedAccessTokenAudience(), "requested_access_token_audience", hydra);
                    oidc(r.getOidcContext(), hydra);
                }
                case CONSENT -> {
                    var r = api.getOAuth2ConsentRequest(challenge);
                    client = r.getClient();
                    put(r.getSkip(), "skip", hydra);
                    put(r.getSubject(), "subject", hydra);
                    put(r.getRequestedScope(), "requested_scope", hydra);
                    put(r.getRequestedAccessTokenAudience(), "requested_access_token_audience", hydra);
                    oidc(r.getOidcContext(), hydra);
                    // Set by our login accept; links a consent to the login flow that preceded it.
                    if (r.getContext() instanceof Map<?, ?> context) hydra.put("context", plain(context));
                }
                case LOGOUT -> {
                    var r = api.getOAuth2LogoutRequest(challenge);
                    client = r.getClient();
                    put(r.getSubject(), "subject", hydra);
                }
                default -> throw new IllegalStateException("unknown kind " + kind);
            }
            return new HostRequest(kind, challenge, client(client), hydra);
        } catch (ApiException e) {
            throw failure("GET", kind.section(), e);
        }
    }

    @Override
    public Redirect accept(HostRequest request, Authenticated outcome) {
        AcceptOAuth2LoginRequest body = new AcceptOAuth2LoginRequest()
                .subject(outcome.subject())
                .amr(outcome.amr())
                .remember(false)
                .context(outcome.context());
        if (outcome.acr() != null) body.acr(outcome.acr());
        try {
            return redirect(api.acceptOAuth2LoginRequest(request.challenge(), body));
        } catch (ApiException e) {
            throw failure("PUT", "login/accept", e);
        }
    }

    @Override
    public Redirect accept(HostRequest request, Consented outcome) {
        AcceptOAuth2ConsentRequest body = new AcceptOAuth2ConsentRequest()
                .grantScope(outcome.grantScope())
                .grantAccessTokenAudience(outcome.grantAudience())
                .remember(false)
                .session(new AcceptOAuth2ConsentRequestSession().idToken(outcome.idTokenClaims()));
        try {
            return redirect(api.acceptOAuth2ConsentRequest(request.challenge(), body));
        } catch (ApiException e) {
            throw failure("PUT", "consent/accept", e);
        }
    }

    @Override
    public Redirect reject(HostRequest request, String error, String description) {
        try {
            return switch (request.kind()) {
                case LOGIN ->
                    redirect(api.rejectOAuth2LoginRequest(
                            request.challenge(),
                            new RejectOAuth2Request().error(error).errorDescription(description)));
                case CONSENT ->
                    redirect(api.rejectOAuth2ConsentRequest(
                            request.challenge(),
                            new RejectOAuth2Request().error(error).errorDescription(description)));
                case LOGOUT -> {
                    // The generated logout reject carries no body and Hydra answers with no redirect
                    api.rejectOAuth2LogoutRequest(request.challenge());
                    throw new IllegalStateException("hydra response has no redirect_to");
                }
            };
        } catch (ApiException e) {
            throw failure("PUT", request.kind().section() + "/reject", e);
        }
    }

    private static IllegalStateException failure(String method, String path, ApiException e) {
        return new IllegalStateException(
                "hydra " + method + " /admin/oauth2/auth/requests/" + path + ": " + e.getCode() + " "
                        + e.getResponseBody(),
                e);
    }

    private static Map<String, Object> client(@Nullable OAuth2Client c) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (c == null) {
            out.put("first_party", false);
            return out;
        }
        if (c.getClientId() != null) out.put("client_id", c.getClientId());
        String name = c.getClientName();
        if (name != null && !name.isEmpty()) out.put("client_name", name);
        Map<?, ?> metadata = c.getMetadata() instanceof Map<?, ?> m ? m : Map.of();
        if (!metadata.isEmpty()) out.put("metadata", plain(metadata));
        // Only clients registered with metadata.first_party = true are first party.
        out.put("first_party", Boolean.TRUE.equals(metadata.get("first_party")));
        return out;
    }

    private static void oidc(@Nullable OAuth2ConsentRequestOpenIDConnectContext oidc, Map<String, Object> hydra) {
        if (oidc == null) return;
        put(oidc.getAcrValues(), "acr_values", hydra);
        put(oidc.getUiLocales(), "ui_locales", hydra);
        put(oidc.getLoginHint(), "login_hint", hydra);
    }

    private static void put(@Nullable Boolean value, String field, Map<String, Object> to) {
        if (value != null) to.put(field, value);
    }

    private static void put(@Nullable String value, String field, Map<String, Object> to) {
        if (value != null && !value.isEmpty()) to.put(field, value);
    }

    private static void put(@Nullable List<String> value, String field, Map<String, Object> to) {
        if (value != null) to.put(field, List.copyOf(value));
    }

    private static Redirect redirect(@Nullable OAuth2RedirectTo body) {
        String url = body == null ? null : body.getRedirectTo();
        if (url == null) throw new IllegalStateException("hydra response has no redirect_to");
        return new Redirect(url);
    }

    /**
     * Gson reads free form objects into maps whose numbers are all {@code Double}; converts them
     * back to the plain Java values Jackson produced: integral numbers as {@code Integer} or
     * {@code Long} (ADR 0021).
     */
    @Nullable
    static Object plain(@Nullable Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), plain(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(HydraFlowHost::plain).toList();
        }
        if (value instanceof Double d && d == Math.rint(d) && Math.abs(d) < 9.0e18) {
            long l = d.longValue();
            return l == (int) l ? (Object) (int) l : l;
        }
        return value;
    }
}
