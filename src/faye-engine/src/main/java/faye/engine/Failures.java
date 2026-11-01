package faye.engine;

/**
 * Told of every run that ends abnormally, preview or not: any end but {@code succeed}, a write that
 * failed to commit, and a host that could not be told ({@code host_error}). An embedder logs it,
 * resolving the site against the version's source map; the engine itself only reports paths
 * (ADR 0027). Called once the state the run led to is saved, before the host is told of a failure,
 * so the reference is in the log whatever the host does.
 */
@FunctionalInterface
public interface Failures {

    /** Failures go unreported. */
    Failures NONE = (state, site) -> {};

    void failed(FlowState state, FailureSite site);
}
