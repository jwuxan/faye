package faye.engine;

import java.util.Map;

/**
 * A lasting change a {@code write} handler asks for, as data. The engine saves it with the flow
 * state that the run produced, in the same compare and set, and only then applies it through the
 * handler's {@link Handler#commit}; a submit that loses the race therefore changes nothing. A
 * commit must be idempotent by content, because a crash between saving and applying applies it
 * again when the flow is next loaded.
 *
 * @param handler the handler that returned it and commits it
 * @param data what to write; plain JSON values
 */
public record Effect(String handler, Map<String, Object> data) {

    public Effect {
        data = Map.copyOf(data);
    }
}
