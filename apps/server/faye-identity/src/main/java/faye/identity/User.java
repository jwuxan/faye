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
 * @param tenant the only tenant whose clients' flows find this account; null for an account every
 *     tenant shares
 * @param phone phone number (E.164), or null
 * @param phoneVerified the person proved they hold {@code phone}
 * @param phoneCodes the person's switch: codes may be texted to {@code phone} (ADR 0032)
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
        boolean preview,
        @Nullable String tenant,
        @Nullable String phone,
        boolean phoneVerified,
        boolean phoneCodes) {

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

    /** An account without a phone. */
    public User(
            String subject,
            String identifier,
            @Nullable String name,
            boolean emailVerified,
            @Nullable String passwordHash,
            @Nullable String totpSecret,
            long totpLastStep,
            List<Passkey> passkeys,
            boolean mfaRequired,
            boolean preview,
            @Nullable String tenant) {
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
                preview,
                tenant,
                null,
                false,
                false);
    }

    /** The number codes may be texted to: verified, with phone codes on; null otherwise. */
    public @Nullable String textablePhone() {
        return phoneVerified && phoneCodes ? phone : null;
    }

    /** An account every tenant shares. */
    public User(
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
                preview,
                null);
    }

    /** An account every tenant shares that is not a preview test account. */
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
                preview,
                tenant,
                phone,
                phoneVerified,
                phoneCodes);
    }

    User withPhone(String phone) {
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
                preview,
                tenant,
                phone,
                true,
                true);
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
                preview,
                tenant,
                phone,
                phoneVerified,
                phoneCodes);
    }
}
