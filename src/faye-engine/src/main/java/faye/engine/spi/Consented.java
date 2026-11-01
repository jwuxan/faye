package faye.engine.spi;

import java.util.List;
import java.util.Map;

/**
 * What a consent entry grants. Faye issues no tokens: which of the person's attributes become
 * claims, under which names and scopes, is the host's decision (charter non-goal 2, ADR 0025).
 *
 * @param user what the flow's handlers learned about the person ({@code user.*} of the login),
 *     for the host to turn into claims
 */
public record Consented(String subject, List<String> grantScope, List<String> grantAudience, Map<String, Object> user) {

    public Consented {
        grantScope = List.copyOf(grantScope);
        grantAudience = List.copyOf(grantAudience);
        user = Map.copyOf(user);
    }
}
