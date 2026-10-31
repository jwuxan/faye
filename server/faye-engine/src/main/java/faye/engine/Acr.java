package faye.engine;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Authentication context class, computed only from the amr values that verified. Two distinct
 * factor classes (a user verified passkey proves two: {@code hwk} and {@code user}) give
 * {@code faye:2fa}; one gives {@code faye:1fa}.
 */
public final class Acr {

    public static final String ONE_FACTOR = "faye:1fa";
    public static final String TWO_FACTOR = "faye:2fa";

    private static final Map<String, Integer> LEVELS = Map.of(ONE_FACTOR, 1, TWO_FACTOR, 2);
    /** amr value to factor class; an amr value not listed is its own class. */
    private static final Map<String, String> CLASSES =
            Map.of("pwd", "knowledge", "otp", "possession", "hwk", "possession", "user", "inherence");

    private Acr() {}

    /** @return null when nothing verified */
    public static @Nullable String of(List<String> amr) {
        if (amr.isEmpty()) return null;
        Set<String> classes = new HashSet<>();
        amr.forEach(a -> classes.add(CLASSES.getOrDefault(a, a)));
        return classes.size() >= 2 ? TWO_FACTOR : ONE_FACTOR;
    }

    /**
     * Whether {@code achieved} meets a request's {@code acr_values}: at least as strong as the
     * weakest value Faye knows. Unknown values are ignored; no known value means no requirement.
     */
    public static boolean satisfies(@Nullable String achieved, @Nullable Object requested) {
        if (!(requested instanceof List<?> values)) return true;
        int needed = values.stream()
                .filter(LEVELS::containsKey)
                .mapToInt(LEVELS::get)
                .min()
                .orElse(0);
        return needed == 0 || (achieved != null && LEVELS.getOrDefault(achieved, 0) >= needed);
    }
}
