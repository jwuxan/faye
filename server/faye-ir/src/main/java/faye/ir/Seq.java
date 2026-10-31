package faye.ir;

import java.util.List;

public record Seq(List<Node> children, SourceRef source) implements Node {

    public Seq {
        children = List.copyOf(children);
        if (children.isEmpty()) {
            throw new IllegalArgumentException("seq needs at least one child");
        }
    }
}
