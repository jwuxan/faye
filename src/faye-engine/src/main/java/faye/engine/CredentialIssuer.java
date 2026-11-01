package faye.engine;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Issues the credential material of one screen type. Which keys a screen needs is part of that
 * screen's contract with its issuer (the IR specification, Screen catalog): the built in
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
     * {@link #issue(Env, Map, Map)} for a flow the issuer must know: a delivered code is sent for
     * one tenant's account, and MACed for one flow. The engine always calls this one; by default it
     * ignores the context.
     */
    default Map<String, Object> issue(
            Env env, Context context, Map<String, Object> opts, Map<String, Object> previous) {
        return issue(env, opts, previous);
    }

    /**
     * Answers an action the screen declares ({@link ScreenSpec#actions()}): resending a code,
     * sending the first code to a number being added (ADR 0032). It is not an answer: no handler
     * runs, no attempt is spent and the flow does not move; the engine stores the returned data under
     * the same compare and set as an answer and shows the screen again. Never called for an action the
     * screen does not declare.
     *
     * @param issued the screen's stored data
     * @param input what the person posted with the action, already checked by {@link
     *     ScreenSpec#acceptsAction}
     * @throws RuntimeException when the material cannot be issued; the screen shows {@link
     *     Engine#ISSUER_ERROR} and stays as it was
     */
    default Acted act(
            Env env,
            Context context,
            Map<String, Object> opts,
            Map<String, Object> issued,
            String action,
            Map<String, Object> input) {
        throw new UnsupportedOperationException("issuer of " + screen() + " takes no action " + action);
    }

    /**
     * The flow an issuer issues for.
     *
     * @param flowId the flow; a code is MACed for it, so no other flow's state can hold it
     * @param tenant the tenant whose flow runs, as the host decided it
     * @param preview the flow runs for a preview client
     * @param ref the host's id for the request the flow runs for (a QR approval's grant, ADR 0032)
     */
    record Context(String flowId, String tenant, boolean preview, String ref) {
        /** For a flow whose host reference the issuer does not need. */
        public Context(String flowId, String tenant, boolean preview) {
            this(flowId, tenant, preview, "");
        }
    }

    /**
     * What an action left.
     *
     * @param data the keys of the screen's data the action replaces or adds; the rest stay
     * @param error shown on the screen (a resend refused by a limit: {@code send_limited}); null when
     *     the action did what it was asked
     */
    record Acted(Map<String, Object> data, @Nullable String error) {
        public Acted {
            data = Map.copyOf(data);
        }

        public static Acted done(Map<String, Object> data) {
            return new Acted(data, null);
        }

        public static Acted refused(String error) {
            return new Acted(Map.of(), error);
        }
    }

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
