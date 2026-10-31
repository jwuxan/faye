package faye.engine;

/** Either the next screen to show or where to send the browser. */
public sealed interface EngineResponse permits ScreenView, Redirect {}
