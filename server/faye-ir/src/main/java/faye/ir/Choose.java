package faye.ir;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code (choose (opt ...) ... {:pick hook})}: offers the enabled options and runs the one chosen.
 *
 * @param pick a hook that runs first and may pick one of the enabled options, or null
 */
public record Choose(List<Opt> options, @Nullable Hook pick, SourceRef source) implements Node {

    /**
     * {@code (opt id body {:guard predicate})}: one alternative.
     *
     * @param guard enables the option when it holds; null for an option that is always enabled
     * @param guardSource where the guard came from; findings about it point there
     */
    public record Opt(String id, Node body, @Nullable Predicate guard, SourceRef source, SourceRef guardSource) {}

    public Choose {
        options = List.copyOf(options);
    }
}
