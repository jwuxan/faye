package faye.engine;

import faye.ir.Hook;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Runs a pinned hook in a sandbox. The engine never trusts what comes back: it accepts only the
 * closed set of results in {@link HookOutput} and treats anything else as a failed step.
 */
public interface HookRunner {

    /** For engines whose flows have no hooks; every run fails. */
    HookRunner NONE = (tenantId, hook, ctx) -> {
        throw new HookException(hook.id() + ": no hook runtime configured");
    };

    /**
     * @param ctx read only snapshot of {@code request}, {@code client}, {@code hydra}, {@code tenant},
     *     {@code user}, {@code vars} and, for a hook that picks, {@code options}; never {@code auth}
     * @return the hook's result as JSON values (maps, lists, strings, numbers, booleans, null)
     * @throws HookException if the hook is missing, throws, or exceeds a limit
     */
    @Nullable
    Object run(String tenantId, Hook hook, Map<String, Object> ctx);
}
