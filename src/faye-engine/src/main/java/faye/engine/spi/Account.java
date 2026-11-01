package faye.engine.spi;

import org.jspecify.annotations.Nullable;

/**
 * An account as the engine needs it.
 *
 * @param tenant the only tenant whose flows may see this account; null for every tenant
 * @param preview a test account only preview clients' flows may see
 * @param phone the verified phone number (E.164) codes may be texted to, set only while the person
 *     has phone codes turned on (ADR 0032); null otherwise. Flows never read it, only {@code
 *     user.has_phone}
 */
public record Account(
        String subject,
        String identifier,
        @Nullable String name,
        boolean emailVerified,
        boolean hasPassword,
        boolean hasTotp,
        boolean hasPasskey,
        boolean mfaRequired,
        @Nullable String tenant,
        boolean preview,
        @Nullable String phone) {

    /** An account without phone codes. */
    public Account(
            String subject,
            String identifier,
            @Nullable String name,
            boolean emailVerified,
            boolean hasPassword,
            boolean hasTotp,
            boolean hasPasskey,
            boolean mfaRequired,
            @Nullable String tenant,
            boolean preview) {
        this(
                subject,
                identifier,
                name,
                emailVerified,
                hasPassword,
                hasTotp,
                hasPasskey,
                mfaRequired,
                tenant,
                preview,
                null);
    }

    /** Whether codes may be texted to this account. */
    public boolean hasPhone() {
        return phone != null;
    }
}
