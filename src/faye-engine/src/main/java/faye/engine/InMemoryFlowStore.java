package faye.engine;

import faye.engine.spi.FlowStore;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test store. Keeps the JSON form, like the SQLite store does, so nothing survives that would not
 * survive a restart.
 */
public final class InMemoryFlowStore implements FlowStore {

    private final Map<String, String> states = new ConcurrentHashMap<>();

    @Override
    public Optional<FlowState> load(String flowId) {
        return Optional.ofNullable(states.get(flowId)).flatMap(FlowStateCodec::read);
    }

    @Override
    public void create(FlowState state) {
        if (states.putIfAbsent(state.flowId(), FlowStateCodec.write(state)) != null) {
            throw new IllegalStateException("flow exists: " + state.flowId());
        }
    }

    @Override
    public boolean replace(FlowState next) {
        boolean[] replaced = {false};
        states.computeIfPresent(next.flowId(), (id, current) -> {
            if (FlowStateCodec.read(current).map(FlowState::revision).orElse(-1L) != next.revision() - 1) {
                return current;
            }
            replaced[0] = true;
            return FlowStateCodec.write(next);
        });
        return replaced[0];
    }
}
