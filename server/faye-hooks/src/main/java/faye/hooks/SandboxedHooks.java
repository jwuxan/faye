package faye.hooks;

import faye.engine.HookException;
import faye.engine.HookRunner;
import faye.engine.HookStore;
import faye.engine.TenantPolicies;
import faye.engine.TenantPolicy;
import faye.ir.Hook;
import faye.ir.HookDef;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The engine's {@link HookRunner}: finds a pinned hook by hash and runs it in the {@link
 * HookRuntime} with only the egress targets it both declared and its tenant granted. The checker
 * refuses to publish a flow whose hook needs a target its tenant has not granted; this check holds
 * the same rule at run time, after a grant is taken away.
 */
public final class SandboxedHooks implements HookRunner {

    private static final System.Logger LOG = System.getLogger(SandboxedHooks.class.getName());

    private final HookStore store;
    private final HookRuntime runtime;
    private final Egress egress;
    private final TenantPolicies tenants;

    /** @param tenants policy per tenant id; a tenant not listed grants no egress */
    public SandboxedHooks(HookStore store, HookRuntime runtime, Egress egress, Map<String, TenantPolicy> tenants) {
        this(store, runtime, egress, TenantPolicies.of(tenants));
    }

    /** @param tenants looked up on every run */
    public SandboxedHooks(HookStore store, HookRuntime runtime, Egress egress, TenantPolicies tenants) {
        this.store = store;
        this.runtime = runtime;
        this.egress = egress;
        this.tenants = tenants;
    }

    @Override
    public @Nullable Object run(String tenantId, Hook hook, Map<String, Object> ctx) {
        HookDef def;
        try {
            def = store.get(hook.hash())
                    .orElseThrow(() -> new HookException(hook.id() + ": no hook stored under " + hook.hash()));
        } catch (HookException e) {
            throw e;
        } catch (RuntimeException e) {
            // The store is down or holds something unreadable: the step fails, not the request
            throw new HookException(hook.id() + ": cannot read " + hook.hash() + ": " + e, e);
        }
        return execute(tenantId, hook.id(), def, ctx, line -> {});
    }

    /**
     * Runs a hook definition that is not stored, as a published one would run for the tenant: an
     * author's test run. The result is returned as is; the engine's checks on it do not apply here.
     *
     * @param log receives each line the hook logs, besides the server log
     */
    public @Nullable Object test(
            String tenantId, String hookId, HookDef def, Map<String, Object> ctx, Consumer<String> log) {
        return execute(tenantId, hookId, def, ctx, log);
    }

    private @Nullable Object execute(
            String tenantId, String hookId, HookDef def, Map<String, Object> ctx, Consumer<String> log) {
        Set<String> granted = tenants.get(tenantId).egress();
        String tag = "hook " + tenantId + "/" + hookId + ": ";
        Capabilities cap = new Capabilities() {
            @Override
            public @Nullable Object getJson(String target, Map<String, Object> query, Duration timeout) {
                if (!def.http().contains(target) || !granted.contains(target)) {
                    throw new HookException("egress target " + target + " is not granted to this hook");
                }
                return egress.getJson(target, query, timeout);
            }

            @Override
            public void log(String line) {
                LOG.log(Level.INFO, tag + line);
                log.accept(line);
            }
        };
        try {
            return runtime.run(def, ctx, cap);
        } catch (HookException e) {
            LOG.log(Level.WARNING, tag + e.getMessage());
            throw e;
        }
    }
}
