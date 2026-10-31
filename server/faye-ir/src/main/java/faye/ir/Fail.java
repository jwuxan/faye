package faye.ir;

public record Fail(String reason, SourceRef source) implements Node {}
