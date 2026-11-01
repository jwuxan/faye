package faye.dsl;

import faye.ir.SourceRef;

public record Diagnostic(SourceRef source, String message) {

    @Override
    public String toString() {
        return source + ": " + message;
    }
}
