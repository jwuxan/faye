package faye.ir;

import java.util.regex.Pattern;

/**
 * {@code (hook id hash)}: runs the tenant JavaScript stored under {@code hash}. It may only set
 * {@code vars}, fail, or, as the first child of a {@code choose}, pick one of its enabled options.
 */
public record Hook(String id, String hash, SourceRef source) implements Node {

    public static final Pattern HASH = Pattern.compile("sha256:[0-9a-f]{64}");

    public Hook {
        if (!HASH.matcher(hash).matches()) throw new IllegalArgumentException("bad hook hash " + hash);
    }
}
