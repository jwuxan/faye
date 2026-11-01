package faye.app;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** {@link TenantConfigStore} for tests. */
final class InMemoryTenantConfigStore implements TenantConfigStore {

    private final Map<String, String> drafts = new ConcurrentHashMap<>();
    private final Map<String, String> sources = new ConcurrentHashMap<>();
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
    public void saveSource(String versionHash, String yaml) {
        sources.putIfAbsent(versionHash, yaml);
    }

    @Override
    public void replaceSource(String versionHash, String yaml) {
        sources.put(versionHash, yaml);
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
