package faye.engine.testing;

import faye.engine.spi.Account;
import faye.engine.spi.OtpEnrollment;
import faye.engine.spi.OtpIssuer;
import faye.engine.spi.OtpVerifier;
import faye.engine.spi.PasskeyAssertion;
import faye.engine.spi.PasskeyAuthenticator;
import faye.engine.spi.PasskeyCredential;
import faye.engine.spi.PasswordAuthenticator;
import faye.engine.spi.PhoneDirectory;
import faye.engine.spi.UserDirectory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;

/**
 * Deterministic in memory implementations of the credential SPIs, for the engine's own tests and
 * for hosts that embed without a real identity backend. Codes are {@code <secret>:<step>} and the
 * "current" TOTP step is fixed at {@link #NOW}; a registration response verifies when its JSON names
 * {@code "credentialId"}, an assertion when it names a credential id the account holds.
 */
public final class InMemoryCredentials
        implements UserDirectory, PasswordAuthenticator, OtpIssuer, OtpVerifier, PasskeyAuthenticator, PhoneDirectory {

    /** The time step {@link #verify} treats as now. */
    public static final long NOW = 123_456L;

    private record UserRec(
            Account account,
            @Nullable String password,
            @Nullable String totpSecret,
            List<String> passkeyIds,
            long totpLastStep,
            @Nullable String totpLastFlow) {}

    private final Map<String, UserRec> bySubject = new HashMap<>();
    private int counter;

    /** Adds an account; a null password or totpSecret means the factor is absent. */
    public void addUser(
            String subject,
            String identifier,
            @Nullable String name,
            boolean emailVerified,
            @Nullable String password,
            @Nullable String totpSecret,
            List<String> passkeyIds,
            boolean mfaRequired,
            @Nullable String tenant,
            boolean preview) {
        bySubject.put(
                subject,
                new UserRec(
                        new Account(
                                subject,
                                identifier,
                                name,
                                emailVerified,
                                password != null,
                                totpSecret != null,
                                !passkeyIds.isEmpty(),
                                mfaRequired,
                                tenant,
                                preview),
                        password,
                        totpSecret,
                        new ArrayList<>(passkeyIds),
                        -1,
                        null));
    }

    /** Adds an account every tenant shares that is not a preview account. */
    public void addUser(String subject, String identifier, @Nullable String password, @Nullable String totpSecret) {
        addUser(subject, identifier, null, true, password, totpSecret, List.of(), false, null, false);
    }

    /** The TOTP code valid for {@code secret} at {@code step}: six digits, like the real thing. */
    public static String code(String secret, long step) {
        return String.format(java.util.Locale.ROOT, "%06d", step);
    }

    // ---- UserDirectory ----

    @Override
    public Optional<Account> byIdentifier(String identifier) {
        return bySubject.values().stream()
                .filter(u -> u.account().identifier().equals(identifier))
                .map(UserRec::account)
                .findFirst();
    }

    @Override
    public Optional<Account> bySubject(String subject) {
        return Optional.ofNullable(bySubject.get(subject)).map(UserRec::account);
    }

    // ---- PasswordAuthenticator ----

    @Override
    public boolean verify(@Nullable Account account, String identifier, String password) {
        if (account == null) return false;
        UserRec u = bySubject.get(account.subject());
        return u != null && password.equals(u.password());
    }

    // ---- OtpIssuer ----

    @Override
    public OtpEnrollment issue(String subject, String accountName, String issuerName) {
        String secret = "SECRET-" + ++counter;
        return new OtpEnrollment(secret, "mem://totp/" + issuerName + ":" + accountName + "?secret=" + secret);
    }

    // ---- OtpVerifier ----

    @Override
    public boolean verify(String subject, String code, String flowId) {
        UserRec u = bySubject.get(subject);
        if (u == null || u.totpSecret() == null) return false;
        OptionalLong step = parse(code);
        if (step.isEmpty() || Math.abs(step.getAsLong() - NOW) > 1) return false;
        boolean fresh = step.getAsLong() > u.totpLastStep()
                || (step.getAsLong() == u.totpLastStep() && flowId.equals(u.totpLastFlow()));
        if (!fresh) return false;
        bySubject.put(
                subject,
                new UserRec(u.account(), u.password(), u.totpSecret(), u.passkeyIds(), step.getAsLong(), flowId));
        return true;
    }

    @Override
    public OptionalLong checkEnrollment(String secret, String code) {
        OptionalLong step = parse(code);
        return step.isPresent() && Math.abs(step.getAsLong() - NOW) <= 1 ? step : OptionalLong.empty();
    }

    private static OptionalLong parse(String code) {
        try {
            return OptionalLong.of(Long.parseLong(code));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    @Override
    public void enroll(String subject, String secret, long step, String flowId) {
        UserRec u = bySubject.get(subject);
        if (u == null) return;
        bySubject.put(
                subject,
                new UserRec(
                        new Account(
                                subject,
                                u.account().identifier(),
                                u.account().name(),
                                u.account().emailVerified(),
                                u.account().hasPassword(),
                                true,
                                u.account().hasPasskey(),
                                u.account().mfaRequired(),
                                u.account().tenant(),
                                u.account().preview(),
                                u.account().phone()),
                        u.password(),
                        secret,
                        u.passkeyIds(),
                        Math.max(step, u.totpLastStep()),
                        flowId));
    }

    // ---- PhoneDirectory ----

    /** Sets the verified number with phone codes on; for an unknown subject, nothing. */
    @Override
    public void enroll(String subject, String phone) {
        UserRec u = bySubject.get(subject);
        if (u == null) return;
        Account a = u.account();
        bySubject.put(
                subject,
                new UserRec(
                        new Account(
                                subject,
                                a.identifier(),
                                a.name(),
                                a.emailVerified(),
                                a.hasPassword(),
                                a.hasTotp(),
                                a.hasPasskey(),
                                a.mfaRequired(),
                                a.tenant(),
                                a.preview(),
                                phone),
                        u.password(),
                        u.totpSecret(),
                        u.passkeyIds(),
                        u.totpLastStep(),
                        u.totpLastFlow()));
    }

    // ---- PasskeyAuthenticator ----

    @Override
    public List<Long> algorithms() {
        return List.of(-7L, -257L);
    }

    @Override
    public String challenge() {
        return "CHALLENGE-" + ++counter;
    }

    /**
     * A registration verifies when its JSON names {@code "credentialId"}; an assertion when the
     * account holds the named id.
     */
    @Override
    public Optional<PasskeyCredential> verifyRegistration(String subject, String responseJson, String challenge) {
        UserRec u = bySubject.get(subject);
        String id = jsonString(responseJson, "credentialId");
        if (id == null || u == null) return Optional.empty();
        return Optional.of(new PasskeyCredential(id, Map.of("attestedCredentialData", "mem:" + id, "signCount", 0)));
    }

    @Override
    public boolean register(String subject, PasskeyCredential credential) {
        UserRec u = bySubject.get(subject);
        if (u == null) return false;
        for (UserRec other : bySubject.values()) {
            if (other.passkeyIds().contains(credential.credentialId())) {
                return other.account().subject().equals(subject);
            }
        }
        u.passkeyIds().add(credential.credentialId());
        return true;
    }

    @Override
    public Optional<PasskeyAssertion> verifyAssertion(String subject, String responseJson, String challenge) {
        UserRec u = bySubject.get(subject);
        String id = jsonString(responseJson, "credentialId");
        if (u == null || id == null || !u.passkeyIds().contains(id)) return Optional.empty();
        return Optional.of(new PasskeyAssertion(responseJson.contains("\"userVerified\":true")));
    }

    private static @Nullable String jsonString(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int at = json.indexOf(marker);
        if (at < 0) return null;
        int end = json.indexOf('"', at + marker.length());
        return end < 0 ? null : json.substring(at + marker.length(), end);
    }
}
