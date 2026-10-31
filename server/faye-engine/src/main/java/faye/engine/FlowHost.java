package faye.engine;

/** The only way the engine talks to an authorization server. */
public interface FlowHost {

    HostRequest load(HostKind kind, String challenge);

    /** Accepts a login challenge. */
    Redirect accept(HostRequest request, Authenticated outcome);

    /** Accepts a consent challenge. */
    Redirect accept(HostRequest request, Consented outcome);

    Redirect reject(HostRequest request, String error, String description);
}
