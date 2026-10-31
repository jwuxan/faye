package faye.engine;

import java.util.Map;
import java.util.Optional;

/**
 * What the engine needs to run a preview client's flow: the version to run in place of the
 * tenant's current one, the scenario's {@code request.*} overrides, and somewhere to send the trace.
 * A client is a preview client when its metadata says {@code preview: true}. Other flows never
 * reach this interface.
 */
public interface Previews {

    /** Previews off: every preview client's sign in is refused. */
    Previews NONE = new Previews() {
        @Override
        public Optional<FlowVersion> version(String tenantId) {
            return Optional.empty();
        }

        @Override
        public Map<String, Object> request(String tenantId) {
            return Map.of();
        }

        @Override
        public void trace(FlowState state, TraceEvent event) {}
    };

    /**
     * The version a preview client of this tenant runs, stored so the flow can pin it; empty
     * refuses the sign in, e.g. when the tenant's draft fails the checker.
     */
    Optional<FlowVersion> version(String tenantId);

    /** Values a preview flow sees in {@code request.*} in place of the real ones. */
    Map<String, Object> request(String tenantId);

    /** Called with each event of a preview flow, in order, once the state it led to is saved. */
    void trace(FlowState state, TraceEvent event);

    /** A client registered with {@code metadata.preview: true}. */
    static boolean isPreview(Map<String, Object> client) {
        return client.get("metadata") instanceof Map<?, ?> meta && Boolean.TRUE.equals(meta.get("preview"));
    }
}
