package faye.ir;

/** {@code (retry attempts body)}; the schema has its range. */
public record Retry(int attempts, Node body, SourceRef source) implements Node {}
