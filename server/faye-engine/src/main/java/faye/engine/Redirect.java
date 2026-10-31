package faye.engine;

/** Send the browser to {@code url}: the host takes over from there. */
public record Redirect(String url) implements Completion {}
