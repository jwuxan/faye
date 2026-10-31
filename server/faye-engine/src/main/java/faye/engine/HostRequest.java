package faye.engine;

import java.util.Map;

/**
 * A challenge loaded from the authorization server.
 *
 * @param client client id and metadata ({@code metadata.tenant} selects the tenant)
 * @param params the authorization request as the host relays it, which flows read as {@code
 *     host.*}: {@code subject}, {@code skip}, {@code acr_values}, {@code requested_scope}, {@code
 *     login_hint}, {@code context}
 */
public record HostRequest(HostKind kind, String challenge, Map<String, Object> client, Map<String, Object> params) {

    public HostRequest {
        client = Map.copyOf(client);
        params = Map.copyOf(params);
    }
}
