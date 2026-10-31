package faye.engine;

import java.util.List;
import java.util.Optional;

/**
 * Published flow versions and each tenant's current pointer. A version is immutable and is kept
 * once stored, so a flow pinned to it can always finish.
 */
public interface FlowVersions {

    /** Stores {@code version} if it is new, adds it to the tenant's history, and makes it current. */
    FlowVersion publish(String tenantId, FlowVersion version);

    /** Stores {@code version} if it is new, without publishing it, so a preview flow can pin a draft. */
    void store(FlowVersion version);

    Optional<FlowVersion> current(String tenantId);

    Optional<FlowVersion> get(String hash);

    /** Hashes of the versions published for the tenant, first published first. */
    List<String> history(String tenantId);

    /** Points the tenant back at a version it published before; false if it never published it. */
    boolean rollback(String tenantId, String hash);
}
