package faye.ir;

/** {@code (set name value)}: writes a literal to {@code vars.name}. */
public record SetVar(String name, Object value, SourceRef source) implements Node {}
