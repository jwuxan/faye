package faye.engine;

import java.util.Optional;

/** Persistence for suspended flows, keyed by flow id. */
public interface FlowStore {

    Optional<FlowState> load(String flowId);

    void create(FlowState state);

    /** Saves {@code next} iff the stored revision is still {@code next.revision() - 1}. */
    boolean replace(FlowState next);
}
