package faye.engine;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The screens flows may show, by type. */
public final class ScreenRegistry {

    /** The screen a {@code choose} with more than one enabled option waits on. */
    public static final String METHOD_PICKER = "method_picker";

    private final Map<String, ScreenSpec> screens = new LinkedHashMap<>();

    public ScreenRegistry(List<? extends ScreenSpec> screens) {
        for (ScreenSpec s : screens) {
            if (this.screens.put(s.type(), s) != null) {
                throw new IllegalArgumentException("duplicate screen " + s.type());
            }
        }
        if (!this.screens.containsKey(METHOD_PICKER)) {
            throw new IllegalArgumentException("the engine needs a " + METHOD_PICKER + " screen");
        }
    }

    /** The screens of {@code schema/screen.schema.json}. */
    public static ScreenRegistry builtIn() {
        return new ScreenRegistry(List.of(BuiltinScreen.values()));
    }

    public Optional<ScreenSpec> find(String type) {
        return Optional.ofNullable(screens.get(type));
    }

    public Collection<ScreenSpec> all() {
        return screens.values();
    }
}
