package faye.engine.testing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The contract {@link faye.engine.spi.PasswordAuthenticator} implementations must keep: no account
 * fails, a wrong password fails, the right one verifies.
 */
public abstract class PasswordAuthenticatorContract {

    /** The implementation under test. */
    protected abstract faye.engine.spi.PasswordAuthenticator authenticator();

    /** The account this test's store knows, with password {@link #password()}. */
    protected abstract faye.engine.spi.Account account();

    /** The account's password. */
    protected abstract String password();

    @Test
    void theRightPasswordVerifies() {
        assertThat(authenticator().verify(account(), account().identifier(), password()))
                .isTrue();
    }

    @Test
    void aWrongPasswordFails() {
        assertThat(authenticator().verify(account(), account().identifier(), "wrong"))
                .isFalse();
    }

    @Test
    void noAccountFailsLikeAWrongPassword() {
        assertThat(authenticator().verify(null, "nobody@example.com", password()))
                .isFalse();
    }
}
