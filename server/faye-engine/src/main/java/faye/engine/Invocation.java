package faye.engine;

import java.util.Map;

/**
 * One {@code (call handler args)} as the handler sees it.
 *
 * @param env what the flow can read; {@code auth} is the engine's
 * @param input the answer to the screen the flow was waiting on, with that screen's server data
 * @param args the call's literal arguments, valid against the handler's {@link HandlerMeta#args()}
 * @param flowId the flow making the call; a {@code guard} handler marks what it consumes with it
 */
public record Invocation(Env env, Submission input, Map<String, Object> args, String flowId) {

    public Invocation {
        args = Map.copyOf(args);
    }
}
