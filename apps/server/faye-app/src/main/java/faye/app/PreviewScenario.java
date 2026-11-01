package faye.app;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a tenant's next preview runs see in place of the real request, e.g. an IP the reputation
 * service scores as high risk. Only preview clients' flows read it.
 *
 * @param request overrides of {@code request.*}: {@code ip} and {@code user_agent}
 */
record PreviewScenario(Map<String, String> request) {

    static final PreviewScenario NONE = new PreviewScenario(Map.of());

    static final Set<String> FIELDS = Set.of("ip", "user_agent");

    PreviewScenario {
        // Absent or null in a request body: not overridden
        Map<String, String> set = new LinkedHashMap<>();
        if (request != null) {
            request.forEach((k, v) -> {
                if (k != null && v != null) set.put(k, v);
            });
        }
        request = Map.copyOf(set);
    }

    /** Why this scenario cannot be saved, or null. */
    @Nullable
    String problem() {
        for (Map.Entry<String, String> e : request.entrySet()) {
            if (!FIELDS.contains(e.getKey())) return "request may set only ip and user_agent";
            if (e.getValue().length() > 256) return e.getKey() + " must be at most 256 characters";
        }
        return null;
    }
}
