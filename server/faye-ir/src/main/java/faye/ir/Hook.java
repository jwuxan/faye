package faye.ir;

/** {@code (hook id hash)}: runs the tenant JavaScript stored under {@code hash} (docs/roadmap/ir-spec.md). */
public record Hook(String id, String hash, SourceRef source) implements Node {}
