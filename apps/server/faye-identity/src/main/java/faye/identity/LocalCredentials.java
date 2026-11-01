package faye.identity;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.exception.DataConversionException;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.verifier.exception.VerificationException;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The prototype's credential services: the SPIs of {@code faye.engine.spi} over {@link UserStore},
 * Argon2 for passwords, RFC 6238 TOTP, and webauthn4j for passkeys.
 */
public final class LocalCredentials
        implements UserDirectory, PasswordAuthenticator, OtpIssuer, OtpVerifier, PasskeyAuthenticator, PhoneDirectory {

    /**
     * The COSE algorithm ids {@code webauthn.register} verifies, in order of preference: ES256, then
     * RS256. The passkey screen's {@code register} carries them so the Login UI offers exactly what
     * the server accepts (ADR 0022).
     */
    public static final List<Long> PASSKEY_ALGORITHMS = List.of(-7L, -257L);

    private static final ObjectConverter CONVERTER = new ObjectConverter();
    private static final AttestedCredentialDataConverter CREDENTIAL_DATA =
            new AttestedCredentialDataConverter(CONVERTER);
    private static final WebAuthnManager WEBAUTHN = WebAuthnManager.createNonStrictWebAuthnManager(CONVERTER);
    private static final List<PublicKeyCredentialParameters> ALGORITHMS = PASSKEY_ALGORITHMS.stream()
            .map(alg -> new PublicKeyCredentialParameters(
                    PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.create(alg)))
            .toList();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserStore users;
    private final WebAuthnSettings webauthn;
    private final Clock clock;

    public LocalCredentials(UserStore users, WebAuthnSettings webauthn, Clock clock) {
        this.users = users;
        this.webauthn = webauthn;
        this.clock = clock;
    }

    // ---- UserDirectory ----

    @Override
    public Optional<Account> byIdentifier(String identifier) {
        return users.byIdentifier(identifier).map(LocalCredentials::account);
    }

    @Override
    public Optional<Account> bySubject(String subject) {
        return users.bySubject(subject).map(LocalCredentials::account);
    }

    private static Account account(User u) {
        return new Account(
                u.subject(),
                u.identifier(),
                u.name(),
                u.emailVerified(),
                u.passwordHash() != null,
                u.totpSecret() != null,
                !u.passkeys().isEmpty(),
                u.mfaRequired(),
                u.tenant(),
                u.preview(),
                u.textablePhone());
    }

    // ---- PasswordAuthenticator ----

    /** An unknown account still costs one Argon2 verification (enumeration safety). */
    @Override
    public boolean verify(@Nullable Account account, String identifier, String password) {
        String hash = account == null
                ? null
                : users.bySubject(account.subject()).map(User::passwordHash).orElse(null);
        return Passwords.matches(password, hash);
    }

    // ---- OtpIssuer ----

    @Override
    public OtpEnrollment issue(String subject, String accountName, String issuerName) {
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        String encoded = Base32.encode(secret);
        return new OtpEnrollment(encoded, uri(encoded, accountName, issuerName));
    }

    static String uri(String secret, String accountName, String issuerName) {
        return "otpauth://totp/" + encode(issuerName + ":" + accountName) + "?secret=" + secret + "&issuer="
                + encode(issuerName);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ---- OtpVerifier ----

    @Override
    public boolean verify(String subject, String code, String flowId) {
        User u = users.bySubject(subject).orElse(null);
        if (u == null || u.totpSecret() == null) return false;
        long step =
                Totp.match(Base32.decode(u.totpSecret()), code, clock.instant().getEpochSecond());
        return step >= 0 && users.useTotpStep(subject, step, flowId);
    }

    @Override
    public OptionalLong checkEnrollment(String secret, String code) {
        long step = Totp.match(Base32.decode(secret), code, clock.instant().getEpochSecond());
        return step < 0 ? OptionalLong.empty() : OptionalLong.of(step);
    }

    @Override
    public void enroll(String subject, String secret, long step, String flowId) {
        users.enrollTotp(subject, secret, step, flowId);
    }

    // ---- PhoneDirectory ----

    @Override
    public void enroll(String subject, String phone) {
        users.enrollPhone(subject, phone);
    }

    // ---- PasskeyAuthenticator ----

    @Override
    public List<Long> algorithms() {
        return PASSKEY_ALGORITHMS;
    }

    @Override
    public String challenge() {
        byte[] out = new byte[32];
        RANDOM.nextBytes(out);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
    }

    @Override
    public Optional<PasskeyCredential> verifyRegistration(String subject, String responseJson, String challenge) {
        try {
            RegistrationData data = WEBAUTHN.verifyRegistrationResponseJSON(
                    responseJson, new RegistrationParameters(server(challenge), ALGORITHMS, false, true));
            var attestation = data.getAttestationObject();
            var authenticatorData = attestation == null ? null : attestation.getAuthenticatorData();
            AttestedCredentialData credential =
                    authenticatorData == null ? null : authenticatorData.getAttestedCredentialData();
            if (authenticatorData == null || credential == null) return Optional.empty();
            return Optional.of(new PasskeyCredential(
                    base64url(credential.getCredentialId()),
                    Map.of(
                            "attestedCredentialData",
                            base64url(CREDENTIAL_DATA.convert(credential)),
                            "signCount",
                            authenticatorData.getSignCount())));
        } catch (DataConversionException | VerificationException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean register(String subject, PasskeyCredential credential) {
        Object data = credential.data().get("attestedCredentialData");
        long signCount = credential.data().get("signCount") instanceof Number n ? n.longValue() : 0;
        if (!(data instanceof String attested)) return false;
        var passkey = new User.Passkey(
                credential.credentialId(), Base64.getUrlDecoder().decode(attested), signCount);
        return users.addPasskey(subject, passkey);
    }

    @Override
    public Optional<PasskeyAssertion> verifyAssertion(String subject, String responseJson, String challenge) {
        try {
            AuthenticationData data = WEBAUTHN.parseAuthenticationResponseJSON(responseJson);
            User user = users.bySubject(subject).orElse(null);
            var parsedId = data.getCredentialId();
            if (user == null || parsedId == null) return Optional.empty();
            // A copy: webauthn4j marks the array type itself nullable, which NullAway cannot narrow
            byte[] credentialId = Arrays.copyOf(parsedId, parsedId.length);
            String id = base64url(credentialId);
            User.Passkey passkey = user.passkeys().stream()
                    .filter(p -> p.credentialId().equals(id))
                    .findFirst()
                    .orElse(null);
            var handle = data.getUserHandle();
            if (passkey == null
                    || (handle != null && !Arrays.equals(handle, user.subject().getBytes(StandardCharsets.UTF_8)))) {
                return Optional.empty();
            }
            var record = new CredentialRecordImpl(
                    new NoneAttestationStatement(),
                    null,
                    null,
                    null,
                    passkey.signCount(),
                    CREDENTIAL_DATA.convert(passkey.attestedCredentialData()),
                    null,
                    null,
                    null,
                    null);
            WEBAUTHN.verify(
                    data, new AuthenticationParameters(server(challenge), record, List.of(credentialId), false, true));
            var authenticatorData = data.getAuthenticatorData();
            if (authenticatorData == null) return Optional.empty();
            // A monotonic maximum: a count that did not rise is a risk signal, not a failure
            // (WebAuthn Level 3, section 6.1.1); webauthn4j has compared it to the stored one
            users.raiseSignCount(subject, id, authenticatorData.getSignCount());
            return Optional.of(new PasskeyAssertion(authenticatorData.isFlagUV()));
        } catch (DataConversionException | VerificationException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Origin, RP id and the challenge. */
    private ServerProperty server(String challenge) {
        return ServerProperty.builder()
                .origins(webauthn.origins().stream().map(Origin::new).collect(Collectors.toSet()))
                .rpId(webauthn.rpId())
                .challenge(new DefaultChallenge(Base64.getUrlDecoder().decode(challenge)))
                .build();
    }

    private static String base64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
