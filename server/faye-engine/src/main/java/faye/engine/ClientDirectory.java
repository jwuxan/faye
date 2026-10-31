package faye.engine;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Read only view of the OAuth clients the authorization server holds, for the Admin UI. The
 * authorization server owns clients: Faye never registers, changes or deletes one, and the engine
 * does not use this interface.
 */
public interface ClientDirectory {

    /** Every client the authorization server holds. Secrets are never part of it. */
    List<Client> clients();

    /**
     * @param tenant {@code metadata.tenant}, which selects the flow the client's sign ins run; null when
     *     unset
     * @param authMethod how the client authenticates at the token endpoint; {@code none} for a public
     *     client, which has no secret
     * @param createdAt ISO 8601, when the authorization server reports it
     */
    record Client(
            String id,
            String name,
            @Nullable String tenant,
            boolean firstParty,
            boolean preview,
            List<String> redirectUris,
            List<String> grantTypes,
            String authMethod,
            @Nullable String createdAt) {

        public Client {
            redirectUris = List.copyOf(redirectUris);
            grantTypes = List.copyOf(grantTypes);
        }
    }
}
