package faye.engine.testing;

import static org.assertj.core.api.Assertions.assertThat;

import faye.engine.spi.PasskeyCredential;
import org.junit.jupiter.api.Test;

/**
 * The contract {@link faye.engine.spi.PasskeyAuthenticator#register} implementations must keep:
 * idempotent, a credential id one account holds cannot be registered to another, and an unknown
 * subject is refused.
 */
public abstract class PasskeyAuthenticatorContract {

    /** The implementation under test. */
    protected abstract faye.engine.spi.PasskeyAuthenticator authenticator();

    /** A subject this test's store knows. */
    protected abstract String subject();

    /** Another subject this test's store knows. */
    protected abstract String otherSubject();

    /** A fresh credential for {@code credentialId}; called more than once per test. */
    protected abstract PasskeyCredential credential(String credentialId);

    @Test
    void registerIsIdempotent() {
        PasskeyCredential credential = credential("cred-1");
        assertThat(authenticator().register(subject(), credential)).isTrue();
        assertThat(authenticator().register(subject(), credential)).isTrue();
    }

    @Test
    void aCredentialIdAnotherAccountHoldsCannotBeRegistered() {
        assertThat(authenticator().register(subject(), credential("cred-1"))).isTrue();
        assertThat(authenticator().register(otherSubject(), credential("cred-1")))
                .isFalse();
    }

    @Test
    void anUnknownSubjectCannotRegister() {
        assertThat(authenticator().register("nobody", credential("cred-1"))).isFalse();
    }
}
