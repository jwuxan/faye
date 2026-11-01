package faye.engine;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** {@link FlowVersions} for tests and single process use. */
public final class InMemoryFlowVersions implements FlowVersions {

    private final Map<String, FlowVersion> byHash = new HashMap<>();
    private final Map<String, String> current = new HashMap<>();
    private final Map<String, Set<String>> history = new HashMap<>();
    private final Map<String, Map<String, Instant>> published = new HashMap<>();

    @Override
    public synchronized FlowVersion publish(String tenantId, FlowVersion version) {
        byHash.putIfAbsent(version.hash(), version);
        history.computeIfAbsent(tenantId, t -> new LinkedHashSet<>()).add(version.hash());
        published.computeIfAbsent(tenantId, t -> new HashMap<>()).putIfAbsent(version.hash(), Instant.now());
        current.put(tenantId, version.hash());
        return version;
    }

    @Override
    public synchronized void store(FlowVersion version) {
        byHash.putIfAbsent(version.hash(), version);
    }

    @Override
    public synchronized Optional<FlowVersion> current(String tenantId) {
        return Optional.ofNullable(current.get(tenantId)).map(byHash::get);
    }

    @Override
    public synchronized Optional<FlowVersion> get(String hash) {
        return Optional.ofNullable(byHash.get(hash));
    }

    @Override
    public synchronized List<String> history(String tenantId) {
        return List.copyOf(history.getOrDefault(tenantId, Set.of()));
    }

    @Override
    public synchronized Map<String, Instant> publishedAt(String tenantId) {
        return Map.copyOf(published.getOrDefault(tenantId, Map.of()));
    }

    @Override
    public synchronized boolean rollback(String tenantId, String hash) {
        if (!history.getOrDefault(tenantId, Set.of()).contains(hash)) return false;
        current.put(tenantId, hash);
        return true;
    }
}
