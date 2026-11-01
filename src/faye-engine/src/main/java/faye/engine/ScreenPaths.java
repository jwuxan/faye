package faye.engine;

import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The URL path a screen is shown at (ADR 0029). It is a label the Login UI puts in the address bar,
 * never something that selects a screen: the flow alone decides which screen it waits on. A screen's
 * {@value #OPTION} option sets it; without one it is the screen's {@link ScreenSpec#path default}.
 * Either way it starts with the first segment of that default, so every flow's paths read the same:
 * {@code /login}, {@code /challenge/...}, {@code /consent} and {@code /error}.
 */
public final class ScreenPaths {

    /** The screen option every screen takes. */
    public static final String OPTION = "path";

    /** The first segment of every screen that is not one of the fixed ones. */
    public static final String CHALLENGE = "/challenge";

    /** Up to three segments after the first; the same rule as {@code src/schema/screen.schema.json}. */
    private static final Pattern REST = Pattern.compile("(/[a-z0-9][a-z0-9_-]*){0,3}");

    private ScreenPaths() {}

    /** The path a screen with these options, inside this step, is shown at. */
    public static String of(ScreenSpec spec, Map<String, Object> opts, @Nullable String step) {
        return opts.get(OPTION) instanceof String path ? path : spec.path(step);
    }

    /** Why a {@value #OPTION} option is not one this screen may take, or null if it is. */
    public static @Nullable String problem(ScreenSpec spec, Object value) {
        String prefix = prefix(spec);
        if (!(value instanceof String path)
                || !path.startsWith(prefix)
                || !REST.matcher(path.substring(prefix.length())).matches()) {
            return "screen '" + spec.type() + "' takes a path of '" + prefix
                    + "' and up to three lowercase segments, such as '" + prefix + "/name'";
        }
        return null;
    }

    /** The first segment of the screen's default path. */
    private static String prefix(ScreenSpec spec) {
        String path = spec.path(null);
        int end = path.indexOf('/', 1);
        return end < 0 ? path : path.substring(0, end);
    }
}
