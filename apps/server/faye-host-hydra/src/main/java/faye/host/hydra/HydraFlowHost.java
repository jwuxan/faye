package faye.host.hydra;

import faye.engine.FlowException;
import faye.engine.spi.Authenticated;
import faye.engine.spi.Completion;
import faye.engine.spi.Consented;
import faye.engine.spi.FlowHost;
import faye.engine.spi.HostKind;
import faye.engine.spi.HostParams;
import faye.engine.spi.HostRequest;
import faye.engine.spi.Redirect;
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

    /** How long Hydra remembers a sign in the person asked it to: 30 days. */
    static final long REMEMBER_FOR_SECONDS = 30L * 24 * 60 * 60;

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

    /**
     * What the Hydra spelling {@code hydra.} of the YAML surface means: flows written before ADR
     * 0024 say {@code hydra.acr_values} for {@code host.acr_values}. The DSL knows no host; the app
     * hands it these aliases.
     */
    public static final Map<String, String> DSL_PATH_ALIASES = Map.of("hydra", "host");

    /**
     * Maps a Hydra login, consent or logout request into the host contract: {@code oidc_context}'s
     * {@code acr_values}, {@code ui_locales} and {@code login_hint}, the requested scope and
     * audience, {@code skip}, {@code subject}, and for a consent the {@code context} our login
     * accept set.
     */
    @Override
    public HostRequest load(HostKind kind, String ref) {
        try {
            OAuth2Client client;
            HostParams params;
            switch (kind) {
                case LOGIN -> {
                    var r = api.getOAuth2LoginRequest(ref);
                    client = r.getClient();
                    params = params(
                            r.getSubject(),
                            r.getSkip(),
                            r.getRequestedScope(),
                            r.getRequestedAccessTokenAudience(),
                            r.getOidcContext(),
                            Map.of());
                }
                case CONSENT -> {
                    var r = api.getOAuth2ConsentRequest(ref);
                    client = r.getClient();
                    // Set by our login accept; links a consent to the login flow that preceded it.
                    Map<String, Object> context = new LinkedHashMap<>();
                    if (r.getContext() instanceof Map<?, ?> c && plain(c) instanceof Map<?, ?> m) {
                        m.forEach((k, v) -> context.put(String.valueOf(k), v));
                    }
                    params = params(
                            r.getSubject(),
                            r.getSkip(),
                            r.getRequestedScope(),
                            r.getRequestedAccessTokenAudience(),
                            r.getOidcContext(),
                            context);
                }
                case LOGOUT -> {
                    var r = api.getOAuth2LogoutRequest(ref);
                    client = r.getClient();
                    params = params(r.getSubject(), null, null, null, null, Map.of());
                }
                default -> throw new IllegalStateException("unknown kind " + kind);
            }
            Map<String, Object> mapped = client(client);
            return new HostRequest(kind, ref, tenant(mapped), preview(mapped), mapped, params);
        } catch (ApiException e) {
            // A challenge Hydra does not know, or no longer holds, is the browser's: anyone can put
            // any string in the URL, and it is refused as no such sign in, not a 5xx (ADR 0007, B2)
            if (e.getCode() == 400 || e.getCode() == 404 || e.getCode() == 410) {
                throw new FlowException(FlowException.Code.FLOW_NOT_FOUND);
            }
            throw failure("GET", segment(kind), e);
        }
    }

    private static HostParams params(
            @Nullable String subject,
            @Nullable Boolean skip,
            @Nullable List<String> scope,
            @Nullable List<String> audience,
            @Nullable OAuth2ConsentRequestOpenIDConnectContext oidc,
            Map<String, Object> context) {
        return new HostParams(
                subject,
                Boolean.TRUE.equals(skip),
                oidc == null ? List.of() : orEmpty(oidc.getAcrValues()),
                orEmpty(scope),
                orEmpty(audience),
                oidc == null ? null : oidc.getLoginHint(),
                oidc == null ? List.of() : orEmpty(oidc.getUiLocales()),
                context);
    }

    private static List<String> orEmpty(@Nullable List<String> list) {
        return list == null ? List.of() : List.copyOf(list);
    }

    @Override
    public Completion accept(HostRequest request, Authenticated outcome) {
        AcceptOAuth2LoginRequest body = new AcceptOAuth2LoginRequest()
                .subject(outcome.subject())
                .amr(outcome.amr())
                .remember(outcome.remember())
                .context(outcome.context());
        // The person asked to stay signed in (ADR 0033): Hydra keeps its login session, and skips
        // the next sign in from this browser for this long; the engine counts that as one factor
        if (outcome.remember()) body.rememberFor(REMEMBER_FOR_SECONDS);
        if (outcome.acr() != null) body.acr(outcome.acr());
        try {
            return redirect(api.acceptOAuth2LoginRequest(request.ref(), body));
        } catch (ApiException e) {
            throw failure("PUT", "login/accept", e);
        }
    }

    @Override
    public Completion accept(HostRequest request, Consented outcome) {
        AcceptOAuth2ConsentRequest body = new AcceptOAuth2ConsentRequest()
                .grantScope(outcome.grantScope())
                .grantAccessTokenAudience(outcome.grantAudience())
                .remember(false)
                .session(new AcceptOAuth2ConsentRequestSession().idToken(idTokenClaims(request, outcome)));
        try {
            return redirect(api.acceptOAuth2ConsentRequest(request.ref(), body));
        } catch (ApiException e) {
            throw failure("PUT", "consent/accept", e);
        }
    }

    @Override
    public Completion reject(HostRequest request, String error, String description) {
        try {
            return switch (request.kind()) {
                case LOGIN ->
                    redirect(api.rejectOAuth2LoginRequest(
                            request.ref(),
                            new RejectOAuth2Request().error(error).errorDescription(description)));
                case CONSENT ->
                    redirect(api.rejectOAuth2ConsentRequest(
                            request.ref(),
                            new RejectOAuth2Request().error(error).errorDescription(description)));
                case LOGOUT -> {
                    // The generated logout reject carries no body and Hydra answers with no redirect
                    api.rejectOAuth2LogoutRequest(request.ref());
                    throw new IllegalStateException("hydra response has no redirect_to");
                }
                // Faye's own request (QR code sign in, ADR 0032), never one Hydra hands out
                case APPROVE -> throw new IllegalStateException("hydra has no approve requests");
            };
        } catch (ApiException e) {
            throw failure("PUT", segment(request.kind()) + "/reject", e);
        }
    }

    /**
     * The tenant whose flow a client's sign ins run: its {@code metadata.tenant}, as the operator
     * registered it in Hydra; null for the default tenant.
     */
    static @Nullable String tenant(Map<String, Object> client) {
        return client.get("metadata") instanceof Map<?, ?> m && m.get("tenant") instanceof String t ? t : null;
    }

    /** A preview client: registered in Hydra with {@code metadata.preview: true}. */
    static boolean preview(Map<String, Object> client) {
        return client.get("metadata") instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("preview"));
    }

    /**
     * ID token claims from what the login learned, by granted scope (OIDC Core 5.4): {@code email}
     * and {@code email_verified} with scope email, {@code name} with scope profile. A preview client
     * also gets {@code faye_flow_id}, the login's flow id that Hydra carried in the consent's
     * context, so the demo client can find the run's trace.
     */
    public static Map<String, Object> idTokenClaims(HostRequest request, Consented outcome) {
        Map<String, Object> user = outcome.user();
        List<String> scopes = outcome.grantScope();
        Map<String, Object> claims = new LinkedHashMap<>();
        if (scopes.contains("email")) {
            if (user.get("email") != null) claims.put("email", user.get("email"));
            if (user.get("email_verified") != null) claims.put("email_verified", user.get("email_verified"));
        }
        if (scopes.contains("profile") && user.get("name") != null) claims.put("name", user.get("name"));
        if (request.preview() && request.params().context().get("flowId") instanceof String flowId) {
            claims.put("faye_flow_id", flowId);
        }
        return claims;
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

    /** Hydra's admin path segment for a kind of challenge. */
    private static String segment(HostKind kind) {
        return switch (kind) {
            case LOGIN -> "login";
            case CONSENT -> "consent";
            case LOGOUT -> "logout";
            case APPROVE -> throw new IllegalStateException("hydra has no approve requests");
        };
    }
}
