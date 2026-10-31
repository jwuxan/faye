package faye.engine;

/** Either the next screen to show, or how the host completes the request. */
public sealed interface EngineResponse permits ScreenView, Completion {}
