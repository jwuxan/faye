package faye.ir;

/** {@code (when test body)}. */
public record When(Predicate test, Node body, SourceRef source) implements Node {}
