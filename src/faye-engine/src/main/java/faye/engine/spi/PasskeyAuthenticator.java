package faye.engine.spi;

import java.util.List;
import java.util.Optional;

/** Passkey (WebAuthn) ceremonies: challenges, registration and assertion. */
public interface PasskeyAuthenticator {

    /** The COSE algorithm ids offered at registration, most preferred first. */
    List<Long> algorithms();

    /** A fresh challenge, base64url. */
    String challenge();

    /**
     * Verifies a registration response for {@code challenge}; writes nothing.
     *
     * @return the credential to store, or empty when the response does not verify
     */
    Optional<PasskeyCredential> verifyRegistration(String subject, String responseJson, String challenge);

    /**
     * Write effect: stores the credential. Idempotent.
     *
     * @return false when another account holds the credential id, or the subject is unknown
     */
    boolean register(String subject, PasskeyCredential credential);

    /**
     * Guard: verifies an assertion for {@code challenge} against the subject's passkeys and raises
     * the stored sign count (never lowers it).
     *
     * @return empty when the assertion does not verify
     */
    Optional<PasskeyAssertion> verifyAssertion(String subject, String responseJson, String challenge);
}
