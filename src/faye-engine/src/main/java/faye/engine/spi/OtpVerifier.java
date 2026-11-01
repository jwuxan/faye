package faye.engine.spi;

import java.util.OptionalLong;

/** Verifies one time codes against enrolled and pending TOTP secrets (RFC 6238). */
public interface OtpVerifier {

    /**
     * Guard: true iff {@code code} is valid for the subject's enrolled factor, atomically marking
     * its time step used. The same {@code flowId} may use the step again (a double submit of one
     * screen); no other flow can. False for an unknown subject or one not enrolled.
     */
    boolean verify(String subject, String code, String flowId);

    /**
     * Checks {@code code} against a pending enrollment secret, writing nothing.
     *
     * @return the matched time step, or empty
     */
    OptionalLong checkEnrollment(String secret, String code);

    /**
     * Write effect, applied after the flow state is saved: stores {@code secret} for {@code subject}
     * and marks {@code step} used by {@code flowId}. Idempotent.
     */
    void enroll(String subject, String secret, long step, String flowId);
}
