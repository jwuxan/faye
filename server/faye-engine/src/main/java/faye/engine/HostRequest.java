package faye.engine;

import java.util.Map;

/**
 * A challenge loaded from the authorization server.
 *
 * @param client client id and metadata ({@code metadata.tenant} selects the tenant)
 * @param hydra request parameters: skip, acr_values, requested_scope, login_hint
 */
public record HostRequest(HostKind kind, String challenge, Map<String, Object> client, Map<String, Object> hydra) {

    public HostRequest {
        client = Map.copyOf(client);
        hydra = Map.copyOf(hydra);
    }
}
