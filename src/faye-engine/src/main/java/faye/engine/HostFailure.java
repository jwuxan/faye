package faye.engine;

/**
 * The host threw when the engine told it how a flow ended. The flow is over (its state is saved);
 * the person starts again. Carries the {@link FailureSite}, already reported to {@link Failures},
 * so the caller can show its reference; the host's exception is the cause.
 */
public final class HostFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FailureSite site;

    public HostFailure(FailureSite site, RuntimeException cause) {
        super("the host failed to complete the flow (" + site.reference() + ")", cause);
        this.site = site;
    }

    public FailureSite site() {
        return site;
    }
}
