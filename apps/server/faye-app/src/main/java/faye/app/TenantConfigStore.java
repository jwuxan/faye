package faye.app;

import faye.ir.SourceMap;
import java.util.List;
import java.util.Optional;

/**
 * What the admin API keeps besides versions: a draft per tenant, the YAML each version was
 * published from, settings saved over a tenant's configured defaults, and the scenario its preview
 * runs see.
 */
interface TenantConfigStore {

    Optional<String> draft(String tenantId);

    /** Replaces the tenant's draft; drafts are not checked. */
    void saveDraft(String tenantId, String yaml);

    /** The YAML a version was first published from. */
    Optional<String> source(String versionHash);

    /**
     * Keeps the first YAML published for a hash, with the source map of what it compiled to (ADR
     * 0027); later ones compile to the same flow, and neither their YAML nor their map is kept.
     */
    void saveSource(String versionHash, String yaml, SourceMap map);

    /** Where the nodes of a version came from, in the YAML {@link #source} holds; empty if not known. */
    Optional<SourceMap> sourceMap(String versionHash);

    /** Adds the map of a version whose YAML was stored without one; a map already stored stays. */
    void saveSourceMap(String versionHash, SourceMap map);

    /** Hashes of the versions whose YAML is stored without a map. */
    List<String> unmappedSources();

    /** Puts this YAML, and the source map of what it compiled to, in place of the ones kept for a hash. */
    void replaceSource(String versionHash, String yaml, SourceMap map);

    Optional<TenantSettings> settings(String tenantId);

    void saveSettings(String tenantId, TenantSettings settings);

    Optional<PreviewScenario> scenario(String tenantId);

    /** Replaces the scenario the tenant's next preview runs see. */
    void saveScenario(String tenantId, PreviewScenario scenario);
}
