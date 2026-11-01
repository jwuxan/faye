package faye.engine;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * How long a tenant's flows of one entry kind may run (ADR 0035). The engine reads them when an
 * entry starts and records its limits on the flow state; a flow in progress keeps the limits it
 * started with. Either setting may be absent: then the engine sets no limit of that kind, and the
 * store's retention is the only one.
 *
 * @param lifetime how long after its start an entry can still be answered
 * @param idleTimeout how long after the last answer the engine took
 */
public record FlowSettings(
        @Nullable Duration lifetime, @Nullable Duration idleTimeout) {

    /** No limit but the store's. */
    public static final FlowSettings NONE = new FlowSettings(null, null);

    public FlowSettings {
        if (lifetime != null && !lifetime.isPositive()) throw new IllegalArgumentException("lifetime must be positive");
        if (idleTimeout != null && !idleTimeout.isPositive()) {
            throw new IllegalArgumentException("idleTimeout must be positive");
        }
    }

    /** The expiry of an entry that starts at {@code now} under these settings; null with none set. */
    FlowState.@Nullable Expiry start(long now) {
        if (lifetime == null && idleTimeout == null) return null;
        return new FlowState.Expiry(
                lifetime == null ? null : now + lifetime.toMillis(),
                idleTimeout == null ? null : idleTimeout.toMillis(),
                now);
    }
}
