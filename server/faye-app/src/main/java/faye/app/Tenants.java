package faye.app;

import faye.engine.TenantPolicies;
import faye.engine.TenantPolicy;
import java.util.Map;
import java.util.Set;

/**
 * Each configured tenant's effective settings: those saved through the admin API, else its {@code
 * faye.tenants.<id>} config. Egress grants always come from config. A tenant that is not configured
 * gets {@link TenantPolicy#DEFAULT}.
 */
final class Tenants implements TenantPolicies {

    private final FayeProperties props;
    private final TenantConfigStore store;

    Tenants(FayeProperties props, TenantConfigStore store) {
        this.props = props;
        this.store = store;
    }

    boolean exists(String tenantId) {
        return props.tenants().containsKey(tenantId);
    }

    Set<String> ids() {
        return props.tenants().keySet();
    }

    /** Saved settings, else the configured ones, branded with no more than the configured name. */
    TenantSettings settings(String tenantId) {
        return store.settings(tenantId).orElseGet(() -> configured(tenantId));
    }

    TenantSettings configured(String tenantId) {
        FayeProperties.Tenant t = props.tenants().get(tenantId);
        if (t == null) return new TenantSettings(false, null, null, TenantSettings.Branding.NONE);
        String name = t.name() == null || t.name().isBlank() ? null : t.name();
        return new TenantSettings(
                t.mfaRequired(),
                t.handlers(),
                t.screens(),
                name == null ? TenantSettings.Branding.NONE : new TenantSettings.Branding(name, null, null, null),
                t.blocks() == null ? Map.of() : t.blocks());
    }

    Set<String> egress(String tenantId) {
        FayeProperties.Tenant t = props.tenants().get(tenantId);
        return t == null || t.egress() == null ? Set.of() : t.egress();
    }

    /** The policy the tenant would have under {@code settings}. */
    TenantPolicy policy(String tenantId, TenantSettings settings) {
        String name = settings.branding().name();
        if (name != null && name.isBlank()) name = null;
        return new TenantPolicy(
                settings.mfaRequired(),
                settings.handlers(),
                settings.screens(),
                egress(tenantId),
                name == null ? tenantId : name,
                settings.rememberMe());
    }

    @Override
    public TenantPolicy get(String tenantId) {
        if (!exists(tenantId)) return TenantPolicy.DEFAULT;
        return policy(tenantId, settings(tenantId));
    }
}
