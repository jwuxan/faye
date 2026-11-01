package faye.ir;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * The one definition of a node's path: the child indices that lead from an entry's body to it
 * (docs/roadmap/ir-spec.md, Evaluation). A {@code seq}'s children by position, the body of a {@code
 * step}, {@code when} or {@code retry} at index 0, and a {@code choose}'s option bodies by option
 * index. The engine's continuation frames and {@link SourceMap} both address nodes this way, so a
 * path the engine reports always names the node the map positions.
 */
public final class NodePaths {

    /** The longest path {@link #parse} reads; deeper than any document {@link IrCodec} accepts. */
    public static final int MAX_DEPTH = IrCodec.MAX_DEPTH;

    private NodePaths() {}

    /** The node's children in index order; empty for a leaf. */
    public static List<Node> children(Node node) {
        return switch (node) {
            case Seq seq -> seq.children();
            case Step step -> List.of(step.body());
            case When when -> List.of(when.body());
            case Retry retry -> List.of(retry.body());
            case Choose choose ->
                choose.options().stream().map(Choose.Opt::body).toList();
            case Screen s -> List.of();
            case Call c -> List.of();
            case Hook h -> List.of();
            case SetVar s -> List.of();
            case Succeed s -> List.of();
            case Fail f -> List.of();
        };
    }

    /** The node at {@code path} below {@code root}; empty for a path that names none. */
    public static Optional<Node> find(Node root, List<Integer> path) {
        Node node = root;
        for (int i : path) {
            List<Node> children = children(node);
            if (i < 0 || i >= children.size()) return Optional.empty();
            node = children.get(i);
        }
        return Optional.of(node);
    }

    /**
     * The node at {@code path} below {@code root}.
     *
     * @throws IllegalStateException if the path names no node
     */
    public static Node resolve(Node root, List<Integer> path) {
        return find(root, path).orElseThrow(() -> new IllegalStateException("bad path " + path));
    }

    /** Calls {@code visit} with every node below and including {@code root}, parents first. */
    public static void walk(Node root, BiConsumer<List<Integer>, Node> visit) {
        walk(root, new ArrayList<>(), visit);
    }

    private static void walk(Node node, List<Integer> path, BiConsumer<List<Integer>, Node> visit) {
        visit.accept(List.copyOf(path), node);
        List<Node> children = children(node);
        for (int i = 0; i < children.size(); i++) {
            path.add(i);
            walk(children.get(i), path, visit);
            path.removeLast();
        }
    }

    /** The path as text: its indices joined by {@code .}, and the empty string for the root. */
    public static String key(List<Integer> path) {
        StringBuilder out = new StringBuilder();
        for (int i : path) {
            if (!out.isEmpty()) out.append('.');
            out.append(i);
        }
        return out.toString();
    }

    /**
     * The path {@link #key} wrote; empty for any other text. Only the canonical spelling reads: no
     * sign, no leading zero, no empty index, at most {@link #MAX_DEPTH} indices.
     */
    public static Optional<List<Integer>> parse(String key) {
        if (key.isEmpty()) return Optional.of(List.of());
        if (key.length() > MAX_DEPTH * 10) return Optional.empty();
        List<Integer> path = new ArrayList<>();
        for (String part : key.split("\\.", -1)) {
            if (part.isEmpty() || part.length() > 9 || (part.length() > 1 && part.charAt(0) == '0')) {
                return Optional.empty();
            }
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) < '0' || part.charAt(i) > '9') return Optional.empty();
            }
            path.add(Integer.parseInt(part));
            if (path.size() > MAX_DEPTH) return Optional.empty();
        }
        return Optional.of(List.copyOf(path));
    }
}
