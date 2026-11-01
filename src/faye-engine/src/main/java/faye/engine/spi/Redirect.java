package faye.engine.spi;

/**
 * Send the browser to {@code url}: the host takes over from there. {@code failed} marks the
 * redirect of a sign in that failed (the host was told), which the Login UI may keep the person on
 * Faye for instead of following; hosts build redirects without it and the engine sets it.
 */
public record Redirect(String url, boolean failed) implements Completion {

    public Redirect(String url) {
        this(url, false);
    }
}
