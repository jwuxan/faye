package faye.ir;

/** {@code (when test body)} (docs/roadmap/ir-spec.md). */
public record When(Predicate test, Node body, SourceRef source) implements Node {}
