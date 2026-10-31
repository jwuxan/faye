package faye.ir;

/** Runs the body if the predicate holds; otherwise completes without running it. */
public record When(Predicate test, Node body, SourceRef source) implements Node {}
