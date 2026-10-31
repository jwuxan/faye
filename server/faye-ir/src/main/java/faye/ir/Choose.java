package faye.ir;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Offers the enabled options; runs the one chosen.
 *
 * @param pick a hook that runs first and may pick one of the enabled options, or null
 */
public record Choose(@Nullable Hook pick, List<Opt> options, SourceRef source) implements Node {

    /** One alternative. An option is enabled unless its body is a {@code when} whose test is false. */
    public record Opt(String id, Node body, SourceRef source) {}

    public Choose(List<Opt> options, SourceRef source) {
        this(null, options, source);
    }

    public Choose {
        options = List.copyOf(options);
        if (options.isEmpty()) {
            throw new IllegalArgumentException("choose needs at least one option");
        }
        Set<String> ids = new HashSet<>();
        for (Opt o : options) {
            if (!ids.add(o.id())) throw new IllegalArgumentException("duplicate option " + o.id());
        }
    }
}
