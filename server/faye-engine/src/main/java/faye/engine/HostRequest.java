package faye.engine;

import java.util.Map;

/**
 * A request a {@link FlowHost} loaded: a sign in, consent or logout the host wants Faye to run.
 *
 * @param ref the host's opaque id for the request (an authorization server's challenge, or an id an embedding system
 *     created); the engine only hands it back to the host
 * @param client the client the request is for, read as {@code client.*}: {@code client_id}, {@code
 *     client_name}, {@code first_party}, and {@code metadata} whose {@code tenant} selects the
 *     tenant and {@code preview: true} marks a preview client
 * @param params the host contract, read as {@code host.*}
 */
public record HostRequest(HostKind kind, String ref, Map<String, Object> client, HostParams params) {

    public HostRequest {
        client = Map.copyOf(client);
    }
}
