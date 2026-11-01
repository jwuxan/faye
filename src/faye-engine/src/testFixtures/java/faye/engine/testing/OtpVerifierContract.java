package faye.engine.testing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The contract {@link faye.engine.spi.OtpVerifier} implementations must keep: a code verifies once
 * per step, the same flow may reuse its step, another flow cannot, and enrolling a secret marks the
 * step its proof was in as used.
 */
public abstract class OtpVerifierContract {

    /** The implementation under test. */
    protected abstract faye.engine.spi.OtpVerifier verifier();

    /** A subject this test's store knows, with {@link #secret()} enrolled. */
    protected abstract String enrolledSubject();

    /** The secret {@link #enrolledSubject()} is enrolled with. */
    protected abstract String secret();

    /** A subject this test's store knows that has no enrolled factor. */
    protected abstract String unenrolledSubject();

    /** A step the implementation treats as the current time step. */
    protected abstract long currentStep();

    /** The code valid for {@code secret} at {@code step}. */
    protected abstract String code(String secret, long step);

    @Test
    void aCodeIsGoodOnceAcrossFlowsButReusableWithinItsFlow() {
        String code = code(secret(), currentStep());
        assertThat(verifier().verify(enrolledSubject(), code, "flow-a")).isTrue();
        // A double submit of one screen repeats the same code with the same flow id
        assertThat(verifier().verify(enrolledSubject(), code, "flow-a")).isTrue();
        assertThat(verifier().verify(enrolledSubject(), code, "flow-b")).isFalse();
    }

    @Test
    void aLaterStepVerifiesAgain() {
        String subject = enrolledSubject();
        assertThat(verifier().verify(subject, code(secret(), currentStep()), "flow-a"))
                .isTrue();
        assertThat(verifier().verify(subject, code(secret(), currentStep() + 1), "flow-b"))
                .isTrue();
    }

    @Test
    void unknownOrUnenrolledSubjectsNeverVerify() {
        assertThat(verifier().verify("nobody", code(secret(), currentStep()), "flow-a"))
                .isFalse();
        assertThat(verifier().verify(unenrolledSubject(), code(secret(), currentStep()), "flow-a"))
                .isFalse();
    }

    @Test
    void enrollIsIdempotentAndMarksTheStepUsed() {
        String subject = "contract-enrolled";
        long step = currentStep();
        verifier().enroll(subject, secret(), step, "flow-a");
        verifier().enroll(subject, secret(), step, "flow-a");
        // The step its proof was in is used by flow-a: reusable there, refused elsewhere
        assertThat(verifier().verify(subject, code(secret(), step), "flow-a")).isTrue();
        assertThat(verifier().verify(subject, code(secret(), step), "flow-b")).isFalse();
        assertThat(verifier().verify(subject, code(secret(), step + 1), "flow-b"))
                .isTrue();
    }

    @Test
    void checkEnrollmentReturnsTheStepOrEmpty() {
        assertThat(verifier().checkEnrollment(secret(), code(secret(), currentStep())))
                .hasValue(currentStep());
        assertThat(verifier().checkEnrollment(secret(), "not-a-code")).isEmpty();
    }
}
