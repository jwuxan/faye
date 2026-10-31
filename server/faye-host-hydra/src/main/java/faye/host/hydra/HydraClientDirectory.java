package faye.host.hydra;

import faye.engine.ClientDirectory;
import java.net.URI;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import sh.ory.hydra.ApiClient;
import sh.ory.hydra.ApiException;
import sh.ory.hydra.ApiResponse;
import sh.ory.hydra.api.OAuth2Api;
import sh.ory.hydra.model.OAuth2Client;

/**
 * Lists OAuth clients through Hydra's admin API ({@code GET /admin/clients}), following its {@code
 * Link: rel="next"} pages, through Ory's generated Java client (ADR 0021). Read only: Hydra owns
 * clients.
 */
public final class HydraClientDirectory implements ClientDirectory {

    private static final int TIMEOUT_MS = 10_000;
    private static final long PAGE_SIZE = 500;
    /** The next page's token sits in the {@code Link: rel="next"} URL. */
    private static final Pattern NEXT = Pattern.compile("<[^>]+[?&]page_token=([^&>]+)[^>]*>;\\s*rel=\"next\"");
    /** More than this many pages of clients are not listed. */
    private static final int MAX_PAGES = 20;

    private final OAuth2Api api;

    /** @param adminUrl Hydra admin base URL, e.g. {@code http://hydra:4445} */
    public HydraClientDirectory(URI adminUrl) {
        ApiClient client = new ApiClient();
        client.setBasePath(adminUrl.toString());
        client.setConnectTimeout(TIMEOUT_MS);
        client.setReadTimeout(TIMEOUT_MS);
        client.setWriteTimeout(TIMEOUT_MS);
        this.api = new OAuth2Api(client);
    }

    @Override
    public List<Client> clients() {
        List<Client> out = new ArrayList<>();
        @Nullable String pageToken = null;
        for (int page = 0; pageToken != null || page == 0; page++) {
            if (page >= MAX_PAGES) break;
            ApiResponse<List<OAuth2Client>> res;
            try {
                res = api.listOAuth2ClientsWithHttpInfo(PAGE_SIZE, pageToken, null, null);
            } catch (ApiException e) {
                throw new IllegalStateException(
                        "hydra GET /admin/clients: " + e.getCode() + " " + e.getResponseBody(), e);
            }
            if (res.getData() == null) break;
            for (OAuth2Client c : res.getData()) out.add(client(c));
            Map<String, List<String>> headers = res.getHeaders();
            // Header names arrive as sent; HTTP/2 lowercases them
            pageToken = headers == null
                    ? null
                    : headers.entrySet().stream()
                            .filter(e -> e.getKey().equalsIgnoreCase("Link"))
                            .flatMap(e -> e.getValue().stream())
                            .map(NEXT::matcher)
                            .filter(Matcher::find)
                            .map(m -> m.group(1))
                            .findFirst()
                            .orElse(null);
        }
        return out;
    }

    private static Client client(OAuth2Client c) {
        Map<?, ?> metadata = c.getMetadata() instanceof Map<?, ?> m ? m : Map.of();
        Object tenant = metadata.get("tenant");
        var created = c.getCreatedAt();
        return new Client(
                c.getClientId() == null ? "" : c.getClientId(),
                c.getClientName() == null ? "" : c.getClientName(),
                tenant instanceof String t && !t.isEmpty() ? t : null,
                Boolean.TRUE.equals(metadata.get("first_party")),
                Boolean.TRUE.equals(metadata.get("preview")),
                strings(c.getRedirectUris()),
                strings(c.getGrantTypes()),
                c.getTokenEndpointAuthMethod() == null ? "client_secret_basic" : c.getTokenEndpointAuthMethod(),
                created == null ? null : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(created));
    }

    private static List<String> strings(@Nullable List<String> array) {
        return array == null ? List.of() : List.copyOf(array);
    }
}
