package faye.hooks;

import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Named, preconfigured HTTP targets; a hook names a target, never a URL. */
public interface Egress {

    /** For deployments with no targets: every call fails. */
    Egress NONE = (target, query, timeout) -> {
        throw new HookException("unknown egress target " + target);
    };

    /**
     * {@code GET} the target with the query as URL parameters and read its JSON response.
     *
     * @throws HookException if the target is unknown, fails, or does not answer within {@code timeout}
     */
    @Nullable
    Object getJson(String target, Map<String, Object> query, Duration timeout);
}
