package faye.engine;

import faye.ir.Entry;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One typed screen: what the engine stores for it, the payload it sends for it and which answers it
 * takes. Registered in a {@link ScreenRegistry}, like handlers in a {@link HandlerRegistry}, so
 * adding a screen touches neither the engine nor the checker. The engine renders nothing: {@link
 * #render} returns the screen protocol payload, and a separately deployed UI draws it. Credential
 * material on a screen comes from its {@link CredentialIssuer}, never from the spec.
 */
public interface ScreenSpec {

    /** The screen type on the wire and in {@code (screen type)}. */
    String type();

    /** The keys a {@code (screen type options)} may set; the verifier refuses any other. */
    default java.util.Set<String> options() {
        return java.util.Set.of();
    }

    /**
     * Whether this screen shows credential material, which the {@link CredentialIssuer} registered
     * for it issues; the {@link ScreenRegistry} refuses a declaring screen without one.
     */
    default boolean issued() {
        return false;
    }

    /**
     * The screen's own data for a new instance, persisted with the flow while it waits and handed
     * to the handlers that consume the answer as {@link Submission#context()}; the engine adds what
     * the screen's issuer issues.
     *
     * @param opts the screen's IR options
     * @param previous data of the screen the flow last waited on, if it was this same screen node;
     *     an enrollment secret survives a retry so the person does not scan a new QR code
     */
    Map<String, Object> prepare(Env env, Map<String, Object> opts, Map<String, Object> previous);

    /**
     * The screen payload sent to the UI, without {@code errors}, {@code attemptsLeft} and what the
     * screen's issuer presents, which the engine adds.
     */
    Map<String, Object> render(Env env, Map<String, Object> data);

    /**
     * The kinds of entry this screen belongs in; the checker refuses it in any other (a consent
     * screen during the sign in would ask before anyone signed in). All kinds by default.
     */
    default Set<Entry.Kind> entries() {
        return EnumSet.allOf(Entry.Kind.class);
    }

    /**
     * Whether this instance of the screen, with these IR options, acts for the signed in subject
     * (registering a credential for them); the checker refuses a path that reaches it before a
     * credential verified the subject.
     */
    default boolean requiresSubject(Map<String, Object> opts) {
        return false;
    }

    /** Whether a submission is a well formed answer to this instance of the screen. */
    boolean accepts(Submission input, Map<String, Object> data);

    /**
     * The screen that a submission to this one also answers if the flow reaches it before waiting
     * again (a passkey chosen from autofill on the identifier screen answers the passkey screen).
     */
    default Optional<String> hosted(Submission input) {
        return Optional.empty();
    }

    /** Whether an answer hosted for screen {@code hosted} answers this screen with these options. */
    default boolean answeredBy(String hosted, Map<String, Object> opts) {
        return false;
    }
}
