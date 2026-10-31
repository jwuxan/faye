package faye.engine;

/**
 * How a host finishes a request it handed Faye: a {@link Redirect} sends the browser on (a
 * redirecting authorization server), a {@link Handoff} gives the caller a result (a system that
 * started the flow by API). Which one a host returns is the host's fixed choice, never the browser's.
 */
public sealed interface Completion extends EngineResponse permits Redirect, Handoff {}
