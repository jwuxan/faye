package faye.engine;

import java.util.List;
import java.util.Map;

/**
 * What the engine proved when an authenticate entry succeeds. {@code amr} and {@code acr} are
 * computed, never configured.
 *
 * @param remember the person asked to stay signed in on this browser, on a screen that offered it
 *     (ADR 0033); the host decides what that means for its session, Faye keeps none
 */
public record Authenticated(
        String subject, List<String> amr, String acr, Map<String, Object> context, boolean remember) {

    public Authenticated {
        amr = List.copyOf(amr);
        context = Map.copyOf(context);
    }

    /** Without the person asking to stay signed in. */
    public Authenticated(String subject, List<String> amr, String acr, Map<String, Object> context) {
        this(subject, amr, acr, context, false);
    }
}
