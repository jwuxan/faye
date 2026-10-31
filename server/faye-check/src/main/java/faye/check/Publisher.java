package faye.check;

import faye.engine.FlowVersion;
import faye.engine.FlowVersions;
import faye.engine.HandlerRegistry;
import faye.engine.HookStore;
import faye.engine.InMemoryHookStore;
import faye.engine.TenantPolicies;
import faye.engine.TenantPolicy;
import faye.ir.Flow;
import faye.ir.HookDef;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * The only way a flow becomes a tenant's current version: it must pass the checker under that
 * tenant's policy, whether it is new or a rollback to a version published before. The hooks a
 * version pins are stored before the version, so a published version can always run its hooks.
 */
public final class Publisher {

    private final FlowVersions versions;
    private final HookStore hooks;
    private final HandlerRegistry handlers;
    private final TenantPolicies tenants;

    /** @param tenants policy per tenant id; a tenant not listed gets {@link TenantPolicy#DEFAULT} */
    public Publisher(
            FlowVersions versions, HookStore hooks, HandlerRegistry handlers, Map<String, TenantPolicy> tenants) {
        this(versions, hooks, handlers, TenantPolicies.of(tenants));
    }

    /** @param tenants looked up on every check, so a version is checked under the current settings */
    public Publisher(FlowVersions versions, HookStore hooks, HandlerRegistry handlers, TenantPolicies tenants) {
        this.versions = versions;
        this.hooks = hooks;
        this.handlers = handlers;
        this.tenants = tenants;
    }

    /** For flows without hooks. */
    public Publisher(FlowVersions versions, HandlerRegistry handlers, Map<String, TenantPolicy> tenants) {
        this(versions, new InMemoryHookStore(), handlers, tenants);
    }

    public Report check(String tenantId, Flow flow) {
        return check(tenantId, flow, Map.of());
    }

    /** @param newHooks hook definitions by hash that come with the flow, besides those stored */
    public Report check(String tenantId, Flow flow, Map<String, HookDef> newHooks) {
        return check(tenants.get(tenantId), flow, newHooks);
    }

    /** Checks a flow under a policy the tenant does not have yet, e.g. settings about to be saved. */
    public Report check(TenantPolicy policy, Flow flow, Map<String, HookDef> newHooks) {
        return Checker.check(
                flow,
                handlers,
                policy,
                hash -> Optional.ofNullable(newHooks.get(hash)).or(() -> hooks.get(hash)));
    }

    /** @throws CheckFailed if the checker finds errors; nothing is published then */
    public FlowVersion publish(String tenantId, Flow flow) {
        return publish(tenantId, flow, Map.of());
    }

    /**
     * @param newHooks hook definitions by hash that come with the flow; stored if it passes
     * @throws CheckFailed if the checker finds errors; nothing is stored or published then
     */
    public FlowVersion publish(String tenantId, Flow flow, Map<String, HookDef> newHooks) {
        Report report = check(tenantId, flow, newHooks);
        if (!report.ok()) throw new CheckFailed(report);
        newHooks.values().forEach(hooks::put);
        return versions.publish(tenantId, FlowVersion.of(flow));
    }

    /**
     * Points the tenant back at a version it published before. The version is checked again, since
     * the tenant's policy may have changed since it was published.
     *
     * @throws NoSuchElementException if the tenant never published {@code hash}
     * @throws CheckFailed if the version no longer passes the checker
     */
    public FlowVersion rollback(String tenantId, String hash) {
        if (!versions.history(tenantId).contains(hash)) {
            throw new NoSuchElementException("tenant " + tenantId + " never published " + hash);
        }
        FlowVersion version = versions.get(hash).orElseThrow();
        Report report = check(tenantId, version.flow());
        if (!report.ok()) throw new CheckFailed(report);
        versions.rollback(tenantId, hash);
        return version;
    }
}
