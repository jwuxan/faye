package faye.identity;

import java.util.Set;

/**
 * Relying party for passkeys.
 *
 * @param rpId the registrable domain passkeys are scoped to, e.g. {@code localhost}
 * @param origins Login UI origins allowed to perform ceremonies, e.g. {@code http://localhost:3000}
 */
public record WebAuthnSettings(String rpId, Set<String> origins) {

    public WebAuthnSettings {
        origins = Set.copyOf(origins);
    }
}
