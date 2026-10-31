package faye.app;

import faye.check.Publisher;
import faye.dsl.DslException;
import faye.dsl.FlowCompiler;
import faye.engine.FlowState;
import faye.engine.FlowVersion;
import faye.engine.FlowVersions;
import faye.engine.HookStore;
import faye.engine.Previews;
import faye.engine.TraceEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs preview clients' flows on the tenant's draft, once it passes the checker under the tenant's
 * settings, or on the current version when there is no draft. The draft's version and hooks are
 * stored by hash, outside any history, so the flow can pin them. With previews off, or a draft that
 * does not compile or fails the checker, a preview client's sign in is refused.
 */
final class DraftPreviews implements Previews {

    private final FayeProperties props;
    private final TenantConfigStore store;
    private final FlowVersions versions;
    private final HookStore hooks;
    private final Publisher publisher;
    private final Tenants tenants;
    private final TraceStore traces;

    private final FlowCompiler.Options dsl;

    DraftPreviews(
            FayeProperties props,
            TenantConfigStore store,
            FlowVersions versions,
            HookStore hooks,
            Publisher publisher,
            Tenants tenants,
            TraceStore traces,
            FlowCompiler.Options dsl) {
        this.dsl = dsl;
        this.props = props;
        this.store = store;
        this.versions = versions;
        this.hooks = hooks;
        this.publisher = publisher;
        this.tenants = tenants;
        this.traces = traces;
    }

    boolean enabled() {
        return props.previewEnabled();
    }

    @Override
    public Optional<FlowVersion> version(String tenantId) {
        if (!enabled() || !tenants.exists(tenantId)) return Optional.empty();
        Optional<String> draft = store.draft(tenantId);
        if (draft.isEmpty()) return versions.current(tenantId);
        FlowCompiler.Compiled compiled;
        try {
            compiled = EngineConfiguration.compileDraft(
                    Objects.requireNonNull(props.tenants().get(tenantId)).flow(), tenantId, draft.get(), dsl);
        } catch (DslException e) {
            return Optional.empty();
        }
        if (!publisher.check(tenantId, compiled.flow(), compiled.hooks()).ok()) return Optional.empty();
        compiled.hooks().values().forEach(hooks::put);
        FlowVersion version = FlowVersion.of(compiled.flow());
        versions.store(version);
        return Optional.of(version);
    }

    @Override
    public Map<String, Object> request(String tenantId) {
        return new LinkedHashMap<>(
                store.scenario(tenantId).orElse(PreviewScenario.NONE).request());
    }

    @Override
    public void trace(FlowState state, TraceEvent event) {
        traces.append(state.tenantId(), state.flowId(), event);
    }
}
