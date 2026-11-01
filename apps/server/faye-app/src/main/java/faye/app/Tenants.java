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
    private final Set<String> allHandlers;
    private final java.util.function.Function<String, Set<String>> undeliverable;

    /**
     * @param allHandlers every handler the app registers, which a tenant may call unless its settings
     *     narrow them
     * @param undeliverable the handlers of a tenant's code delivery channels that are off (ADR 0032)
     */
    Tenants(
            FayeProperties props,
            TenantConfigStore store,
            Set<String> allHandlers,
            java.util.function.Function<String, Set<String>> undeliverable) {
        this.props = props;
        this.store = store;
        this.allHandlers = Set.copyOf(allHandlers);
        this.undeliverable = undeliverable;
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

    /** The handlers of the tenant's code channels that have no delivery (ADR 0032). */
    Set<String> undeliverable(String tenantId) {
        return undeliverable.apply(tenantId);
    }

    Set<String> egress(String tenantId) {
        FayeProperties.Tenant t = props.tenants().get(tenantId);
        return t == null || t.egress() == null ? Set.of() : t.egress();
    }

    /** The policy the tenant would have under {@code settings}. */
    TenantPolicy policy(String tenantId, TenantSettings settings) {
        String name = settings.branding().name();
        if (name != null && name.isBlank()) name = null;
        // A channel without delivery takes its handlers away, so a flow naming them is refused at publish
        Set<String> off = undeliverable.apply(tenantId);
        Set<String> handlers = settings.handlers();
        if (!off.isEmpty()) {
            Set<String> narrowed = new java.util.TreeSet<>(handlers == null ? allHandlers : handlers);
            narrowed.removeAll(off);
            handlers = narrowed;
        }
        return new TenantPolicy(
                settings.mfaRequired(),
                handlers,
                settings.screens(),
                egress(tenantId),
                name == null ? tenantId : name,
                settings.rememberMe(),
                settings.flowSettings());
    }

    @Override
    public TenantPolicy get(String tenantId) {
        if (!exists(tenantId)) return TenantPolicy.DEFAULT;
        return policy(tenantId, settings(tenantId));
    }
}
