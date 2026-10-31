package faye.identity;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * An account and its credentials.
 *
 * @param identifier normalized email address, also the {@code email} claim
 * @param passwordHash Argon2 hash, or null for an account without a password
 * @param totpSecret base32 TOTP secret, or null when not enrolled
 * @param totpLastStep last TOTP time step accepted, so a code is never accepted twice
 * @param preview a test account seeded for previews; preview clients' flows are the only ones that
 *     find it
 */
public record User(
        String subject,
        String identifier,
        @Nullable String name,
        boolean emailVerified,
        @Nullable String passwordHash,
        @Nullable String totpSecret,
        long totpLastStep,
        List<Passkey> passkeys,
        boolean mfaRequired,
        boolean preview) {

    /**
     * A registered WebAuthn credential.
     *
     * @param credentialId base64url credential id
     * @param attestedCredentialData the credential's public key and AAGUID, as webauthn4j serializes them
     */
    public record Passkey(String credentialId, byte[] attestedCredentialData, long signCount) {}

    public User {
        passkeys = List.copyOf(passkeys);
    }

    /** An account that is not a preview test account. */
    public User(
            String subject,
            String identifier,
            @Nullable String name,
            boolean emailVerified,
            @Nullable String passwordHash,
            @Nullable String totpSecret,
            long totpLastStep,
            List<Passkey> passkeys,
            boolean mfaRequired) {
        this(
                subject,
                identifier,
                name,
                emailVerified,
                passwordHash,
                totpSecret,
                totpLastStep,
                passkeys,
                mfaRequired,
                false);
    }

    User withTotp(@Nullable String secret, long lastStep) {
        return new User(
                subject,
                identifier,
                name,
                emailVerified,
                passwordHash,
                secret,
                lastStep,
                passkeys,
                mfaRequired,
                preview);
    }

    User withPasskeys(List<Passkey> passkeys) {
        return new User(
                subject,
                identifier,
                name,
                emailVerified,
                passwordHash,
                totpSecret,
                totpLastStep,
                passkeys,
                mfaRequired,
                preview);
    }
}
