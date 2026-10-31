package faye.engine;

import java.util.Map;

/**
 * Issues the credential material of one screen type. Which keys a screen needs is part of that
 * screen's contract with its issuer (docs/roadmap/ir-spec.md, Screen catalog): the built in
 * {@code identifier} and {@code passkey} screens need {@code challenge}, {@code otp_enroll} needs
 * {@code secret}; an upstream issuer adds its own reference beside them. It issues a WebAuthn
 * challenge or a TOTP secret being enrolled. Supplied by the credential module whose handlers
 * verify the answer and registered with them, so what the engine stores is what that module, or the
 * upstream service behind it, can check (ADR 0025). The engine only stores and forwards what an issuer returns:
 *
 * <ul>
 *   <li>{@link #issue} output is kept as the waiting screen's data ({@link FlowState.Awaiting#data})
 *       and reaches the handlers that consume the answer as {@link Submission#context()} only. It
 *       is never in {@link Env}, so predicates and hooks cannot read it, and never traced.
 *   <li>{@link #present} output is merged into the screen payload the Login UI renders.
 * </ul>
 *
 * <p>A {@link ScreenSpec} declares that it needs an issuer ({@link ScreenSpec#issued()}); the
 * {@link ScreenRegistry} refuses at construction a declaring screen with none and an issuer for a
 * screen that declares none.
 */
public interface CredentialIssuer {

    /** The screen type this issuer serves, e.g. {@code passkey}. */
    String screen();

    /**
     * Material for a new instance of the screen. An issuer backed by an upstream service has that
     * service create it and returns a reference the service can verify. Its keys must not collide
     * with those the screen's own {@link ScreenSpec#prepare} returns.
     *
     * @param opts the screen's IR options
     * @param previous the data the same screen node waited with last, if the flow last waited on
     *     that node (the spec's keys and this issuer's together); an enrollment secret survives a
     *     retry
     * @throws RuntimeException when the material cannot be issued (an upstream failure or timeout);
     *     the engine fails the step closed with {@link Engine#ISSUER_ERROR}
     */
    Map<String, Object> issue(Env env, Map<String, Object> opts, Map<String, Object> previous);

    /**
     * The fields of the screen payload this material shows (the public half: a challenge, an
     * enrollment URI), merged with what {@link ScreenSpec#render} returns. Local and deterministic: it
     * runs on every view of the screen.
     *
     * @param issued the screen's stored data, which holds what {@link #issue} returned
     */
    Map<String, Object> present(Env env, Map<String, Object> issued);

    /**
     * Whether a stored flow waiting with {@code issued} can still be shown and answered: the engine
     * fails closed a flow whose state does not fit (a corrupt or hand edited row). Every state the
     * engine itself wrote with this issuer must be resumable.
     */
    default boolean resumable(Env env, Map<String, Object> issued) {
        return true;
    }
}
