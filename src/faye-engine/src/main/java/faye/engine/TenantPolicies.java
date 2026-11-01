package faye.engine;

import java.util.Map;

/**
 * Looks up a tenant's policy when a flow starts, a version is checked, or a hook calls out, so a
 * changed setting applies from then on. A flow in progress keeps the settings it started with.
 */
@FunctionalInterface
public interface TenantPolicies {

    /** The tenant's policy; {@link TenantPolicy#DEFAULT} for a tenant with no settings. */
    TenantPolicy get(String tenantId);

    /** Fixed policies; a tenant not listed gets {@link TenantPolicy#DEFAULT}. */
    static TenantPolicies of(Map<String, TenantPolicy> tenants) {
        Map<String, TenantPolicy> copy = Map.copyOf(tenants);
        return id -> copy.getOrDefault(id, TenantPolicy.DEFAULT);
    }
}
