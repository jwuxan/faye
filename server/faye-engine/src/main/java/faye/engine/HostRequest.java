package faye.engine;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A request a {@link FlowHost} loaded: a sign in, consent or logout the host wants Faye to run.
 * The host decides which tenant's flow runs and whether this is a preview; the engine reads
 * neither from the client.
 *
 * @param ref the host's opaque id for the request (an authorization server's challenge, or an id an
 *     embedding system created); the engine only hands it back to the host
 * @param tenant whose flow runs; null for {@link Engine#DEFAULT_TENANT}
 * @param preview the request comes from a preview client: it runs the tenant's draft ({@link
 *     Previews}), sees preview only accounts and is traced. A host sets it only for clients its
 *     operator registered as such, never from anything the caller sent.
 * @param client the client the request is for, display data read as {@code client.*} ({@code
 *     client_id}, {@code client_name}, {@code first_party}, {@code metadata})
 * @param params the host contract, read as {@code host.*}
 */
public record HostRequest(
        HostKind kind,
        String ref,
        @Nullable String tenant,
        boolean preview,
        Map<String, Object> client,
        HostParams params) {

    public HostRequest {
        tenant = tenant == null || tenant.isEmpty() ? null : tenant;
        client = Map.copyOf(client);
    }

    /** The tenant whose flow runs. */
    public String tenantOrDefault() {
        return tenant == null ? Engine.DEFAULT_TENANT : tenant;
    }
}
