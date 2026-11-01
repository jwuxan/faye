package faye.app;

import faye.ir.SourceMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** {@link TenantConfigStore} for tests. */
final class InMemoryTenantConfigStore implements TenantConfigStore {

    private final Map<String, String> drafts = new ConcurrentHashMap<>();
    private final Map<String, String> sources = new ConcurrentHashMap<>();
    private final Map<String, SourceMap> maps = new ConcurrentHashMap<>();
    private final Map<String, TenantSettings> settings = new ConcurrentHashMap<>();
    private final Map<String, PreviewScenario> scenarios = new ConcurrentHashMap<>();

    @Override
    public Optional<String> draft(String tenantId) {
        return Optional.ofNullable(drafts.get(tenantId));
    }

    @Override
    public void saveDraft(String tenantId, String yaml) {
        drafts.put(tenantId, yaml);
    }

    @Override
    public Optional<String> source(String versionHash) {
        return Optional.ofNullable(sources.get(versionHash));
    }

    @Override
    public synchronized void saveSource(String versionHash, String yaml, SourceMap map) {
        if (sources.putIfAbsent(versionHash, yaml) == null) maps.put(versionHash, map);
    }

    @Override
    public Optional<SourceMap> sourceMap(String versionHash) {
        return Optional.ofNullable(maps.get(versionHash));
    }

    @Override
    public void saveSourceMap(String versionHash, SourceMap map) {
        maps.putIfAbsent(versionHash, map);
    }

    @Override
    public List<String> unmappedSources() {
        return sources.keySet().stream()
                .filter(h -> !maps.containsKey(h))
                .sorted()
                .toList();
    }

    @Override
    public synchronized void replaceSource(String versionHash, String yaml, SourceMap map) {
        sources.put(versionHash, yaml);
        maps.put(versionHash, map);
    }

    @Override
    public Optional<TenantSettings> settings(String tenantId) {
        return Optional.ofNullable(settings.get(tenantId));
    }

    @Override
    public void saveSettings(String tenantId, TenantSettings settings) {
        this.settings.put(tenantId, settings);
    }

    @Override
    public Optional<PreviewScenario> scenario(String tenantId) {
        return Optional.ofNullable(scenarios.get(tenantId));
    }

    @Override
    public void saveScenario(String tenantId, PreviewScenario scenario) {
        scenarios.put(tenantId, scenario);
    }
}
