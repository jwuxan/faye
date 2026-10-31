package faye.check;

import faye.ir.SourceRef;

/** A checker error or warning, at the configuration a human wrote. */
public record Finding(SourceRef source, String message) {

    @Override
    public String toString() {
        return source + ": " + message;
    }
}
