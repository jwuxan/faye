package faye.ir;

/** Runs the body up to {@code attempts} times in total while a handler in it fails. */
public record Retry(int attempts, Node body, SourceRef source) implements Node {

    public static final int MAX_ATTEMPTS = 10;

    public Retry {
        if (attempts < 1 || attempts > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("retry attempts must be 1 to " + MAX_ATTEMPTS);
        }
    }
}
