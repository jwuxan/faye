package faye.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The only results a hook may have. A hook can never succeed a flow, skip a step, bind a subject,
 * write {@code auth}, or show a screen, because no result here says so.
 */
public sealed interface HookOutput {

    /** Failure reason of a hook that broke its contract, threw, or exceeded a limit. */
    String HOOK_ERROR = "hook_error";

    int MAX_VARS = 16;
    int MAX_STRING = 1024;
    Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*");
    Pattern REASON = Pattern.compile("[a-z][a-z0-9_-]{0,63}");

    /** {@code {}}: nothing to do. */
    record None() implements HookOutput {}

    /** {@code {set: {name: literal, ...}}}: merge into {@code vars}. */
    record Set(Map<String, Object> vars) implements HookOutput {
        public Set {
            vars = Map.copyOf(vars);
        }
    }

    /** {@code {fail: reason}}: fail the step, subject to an enclosing retry. */
    record Fail(String reason) implements HookOutput {}

    /** {@code {pick: optionId}}: only for the hook of a choose, and only an enabled option. */
    record Pick(String option) implements HookOutput {}

    /** The output a raw result stands for, or empty if it is anything else. */
    static Optional<HookOutput> parse(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.size() > 1) return Optional.empty();
        if (map.isEmpty()) return Optional.of(new None());
        var entry = map.entrySet().iterator().next();
        Object value = entry.getValue();
        return switch (String.valueOf(entry.getKey())) {
            case "set" -> vars(value).map(Set::new);
            case "fail" ->
                value instanceof String s && REASON.matcher(s).matches() ? Optional.of(new Fail(s)) : Optional.empty();
            case "pick" -> value instanceof String s ? Optional.of(new Pick(s)) : Optional.empty();
            default -> Optional.empty();
        };
    }

    /** One to {@link #MAX_VARS} names, each set to a string, integer or boolean. */
    private static Optional<Map<String, Object>> vars(@Nullable Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty() || map.size() > MAX_VARS) return Optional.empty();
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : map.entrySet()) {
            if (!(e.getKey() instanceof String key) || !KEY.matcher(key).matches()) return Optional.empty();
            switch (e.getValue()) {
                case String s when s.length() <= MAX_STRING -> out.put(key, s);
                case Boolean b -> out.put(key, b);
                case Integer i -> out.put(key, i);
                case Long l -> out.put(key, l);
                case null, default -> {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(out);
    }
}
