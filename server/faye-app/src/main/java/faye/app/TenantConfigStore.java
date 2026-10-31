package faye.app;

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

    /** Keeps the first YAML published for a hash; later ones compile to the same flow. */
    void saveSource(String versionHash, String yaml);

    Optional<TenantSettings> settings(String tenantId);

    void saveSettings(String tenantId, TenantSettings settings);

    Optional<PreviewScenario> scenario(String tenantId);

    /** Replaces the scenario the tenant's next preview runs see. */
    void saveScenario(String tenantId, PreviewScenario scenario);
}
