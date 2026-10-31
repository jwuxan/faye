package faye.ir;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** A whole flow: one body per section, in declaration order. */
public record Flow(String id, int version, Map<String, Node> sections) {

    public static final Set<String> SECTIONS = Set.of("login", "consent");

    public Flow {
        if (sections.isEmpty()) {
            throw new IllegalArgumentException("flow needs at least one section");
        }
        if (!SECTIONS.containsAll(sections.keySet())) {
            throw new IllegalArgumentException("unknown section in " + sections.keySet());
        }
        sections = Collections.unmodifiableMap(new LinkedHashMap<>(sections));
    }
}
