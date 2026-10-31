package faye.hooks;

import faye.engine.HookException;
import faye.ir.HookDef;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Runs hook source in a sandbox: no host classes, no I/O but the capabilities handed in, a wall
 * clock deadline and a memory cap per run. Swapping the JavaScript engine means another
 * implementation of this interface, nothing else.
 */
public interface HookRuntime {

    /**
     * Calls the hook's default export with a deep frozen copy of {@code ctx} and {@code cap}.
     *
     * @return what the hook returned (or its promise resolved to), as JSON values
     * @throws HookException if the hook throws, has no default export, or exceeds a limit
     */
    @Nullable
    Object run(HookDef def, Map<String, Object> ctx, Capabilities cap);
}
