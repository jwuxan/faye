package faye.engine;

import java.util.List;
import java.util.Map;

/**
 * What the engine proved when an authenticate entry succeeds. {@code amr} and {@code acr} are
 * computed, never configured.
 */
public record Authenticated(String subject, List<String> amr, String acr, Map<String, Object> context) {

    public Authenticated {
        amr = List.copyOf(amr);
        context = Map.copyOf(context);
    }
}
