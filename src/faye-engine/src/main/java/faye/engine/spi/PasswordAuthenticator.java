package faye.engine.spi;

import org.jspecify.annotations.Nullable;

/** The authentication endpoint for a typed password. */
public interface PasswordAuthenticator {

    /**
     * Whether {@code password} is the account's password.
     *
     * @param account null when no account is visible to this flow for {@code identifier}; an
     *     implementation must still do the work of a real check and return false, so an unknown
     *     identifier costs the same as a known one (enumeration safety)
     */
    boolean verify(@Nullable Account account, String identifier, String password);
}
