package faye.engine.spi;

import faye.engine.Engine;
import faye.engine.Previews;
import java.util.Map;
import java.util.Set;
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

    /**
     * The keys of {@code client} a flow may read as {@code client.<x>}: the client contract. A host
     * fills these and no others; the checker refuses any other path at publish. {@code metadata} is
     * the operator's own data about the client, readable below it.
     */
    public static final Set<String> CLIENT_FIELDS = Set.of("client_id", "client_name", "first_party", "metadata");

    /** The client field whose own keys a flow may read below it. */
    public static final String CLIENT_FREE_FORM = "metadata";

    /** Whether {@code client.<rest>} names a field of the client contract. */
    public static boolean clientReadable(String rest) {
        int dot = rest.indexOf('.');
        String field = dot < 0 ? rest : rest.substring(0, dot);
        return CLIENT_FIELDS.contains(field) && (dot < 0 || field.equals(CLIENT_FREE_FORM));
    }

    /** The tenant whose flow runs. */
    public String tenantOrDefault() {
        return tenant == null ? Engine.DEFAULT_TENANT : tenant;
    }
}
