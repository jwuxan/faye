package faye.engine.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A finished request whose result the caller hands to the system that started it, with no
 * redirect. The result is the host's to define: for example a one time code the system exchanges
 * server to server for the outcome, so {@code amr} and {@code acr} never travel through the browser
 * as claims. Faye reports the outcome; the host turns it into a session (charter non-goal 2).
 *
 * @param result JSON values only
 */
public record Handoff(Map<String, Object> result) implements Completion {

    public Handoff {
        // JSON null is a value; Map.copyOf would refuse it
        result = Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }
}
