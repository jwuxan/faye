package faye.ir;

/** {@code (retry attempts body)}; docs/roadmap/ir-spec.md has its meaning, the schema its range. */
public record Retry(int attempts, Node body, SourceRef source) implements Node {}
