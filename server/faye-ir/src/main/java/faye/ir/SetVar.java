package faye.ir;

/** {@code (set name value)}: writes a literal to {@code vars.name} (docs/roadmap/ir-spec.md). */
public record SetVar(String name, Object value, SourceRef source) implements Node {}
