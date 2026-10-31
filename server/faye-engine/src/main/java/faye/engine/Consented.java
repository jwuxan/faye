package faye.engine;

import java.util.List;
import java.util.Map;

/**
 * What a consent entry grants.
 *
 * @param idTokenClaims identity claims for the ID token, already filtered by granted scope
 */
public record Consented(
        String subject, List<String> grantScope, List<String> grantAudience, Map<String, Object> idTokenClaims) {

    public Consented {
        grantScope = List.copyOf(grantScope);
        grantAudience = List.copyOf(grantAudience);
        idTokenClaims = Map.copyOf(idTokenClaims);
    }
}
