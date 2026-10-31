package faye.engine;

/**
 * The only way the engine talks to the system it runs flows for: an authorization server that
 * delegates its login and consent UI, or an existing system that starts flows by API. An
 * adapter maps its protocol into {@link HostRequest} and the outcome back; nothing about the
 * protocol reaches the engine, the IR or a flow (charter principle 3).
 *
 * <p>The engine calls {@code accept} and {@code reject} only from the request whose saved state
 * finished the flow, after the flow's writes are applied, so each request is completed at most once
 * by Faye. A host that throws fails that request; the person starts again.
 */
public interface FlowHost {

    /** @param ref the host's id for the request, as the caller passed it to {@link Engine#start} */
    HostRequest load(HostKind kind, String ref);

    /** Completes a sign in: the person proved who they are. */
    Completion accept(HostRequest request, Authenticated outcome);

    /** Completes a consent. */
    Completion accept(HostRequest request, Consented outcome);

    /** Refuses the request; {@code description} is a Faye failure code such as {@code mfa_required}. */
    Completion reject(HostRequest request, String error, String description);
}
