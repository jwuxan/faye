package faye.ir;

public record Step(String id, Node body, SourceRef source) implements Node {}
