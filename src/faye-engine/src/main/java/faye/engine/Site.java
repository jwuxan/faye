package faye.engine;

import faye.ir.NodePaths;
import java.util.List;
import java.util.Objects;

/**
 * A node of a running flow's version: its entry, and its path of child indices within the entry's
 * body ({@link NodePaths}). The engine reports where things happened this way and never as a file
 * position; whoever holds the version's {@link faye.ir.SourceMap} turns it into one (ADR 0027).
 */
public record Site(String entry, List<Integer> path) {

    public Site {
        Objects.requireNonNull(entry);
        path = List.copyOf(path);
    }

    /** {@code entry/path}, the path as {@link NodePaths#key}: {@code login/0.2}, {@code login/} for the body. */
    @Override
    public String toString() {
        return entry + "/" + NodePaths.key(path);
    }
}
