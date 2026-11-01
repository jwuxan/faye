package faye.app;

import faye.engine.TraceEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** {@link TraceStore} for tests; nothing expires. */
final class InMemoryTraceStore implements TraceStore {

    private final Map<String, List<Recorded>> flows = new HashMap<>();
    private final Map<String, List<Recorded>> feeds = new HashMap<>();

    @Override
    public synchronized void append(
            String tenantId, String flowId, TraceEvent event, SourceLines.@Nullable Located source) {
        List<Recorded> feed = feeds.computeIfAbsent(tenantId, t -> new ArrayList<>());
        var recorded = new Recorded(feed.size() + 1L, tenantId, flowId, System.currentTimeMillis(), event, source);
        feed.add(recorded);
        flows.computeIfAbsent(flowId, f -> new ArrayList<>()).add(recorded);
    }

    @Override
    public synchronized List<Recorded> flow(String flowId) {
        return List.copyOf(flows.getOrDefault(flowId, List.of()));
    }

    @Override
    public synchronized List<Recorded> feed(String tenantId, long after) {
        return feeds.getOrDefault(tenantId, List.of()).stream()
                .filter(r -> r.seq() > after)
                .toList();
    }

    @Override
    public synchronized long latest(String tenantId) {
        return feeds.getOrDefault(tenantId, List.of()).size();
    }
}
