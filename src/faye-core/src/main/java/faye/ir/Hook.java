package faye.ir;

/** {@code (hook id hash)}: runs the tenant JavaScript stored under {@code hash}. */
public record Hook(String id, String hash, SourceRef source) implements Node {}
