package faye.identity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import faye.engine.Base32;
import faye.engine.Env;
import faye.engine.Handler;
import faye.engine.Outcome;
import faye.engine.Previews;
import faye.engine.Submission;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * The identity handlers: {@code identity.resolve}, {@code password.verify}, {@code totp.verify},
 * {@code totp.enroll}, {@code webauthn.register}, {@code webauthn.assert} and {@code consent.record}.
 *
 * <p>Enumeration safety: an unknown identifier resolves like a known account with only a password,
 * password checks for it cost one Argon2 verification like any other, and every credential
 * failure is the same {@code invalid_credentials} (or {@code invalid_code} for one time codes).
 *
 * <p>Preview test accounts ({@link User#preview()}) exist only for preview clients: for any other
 * client every handler treats them as unknown.
 */
public final class IdentityHandlers {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectConverter CONVERTER = new ObjectConverter();
    private static final AttestedCredentialDataConverter CREDENTIAL_DATA =
            new AttestedCredentialDataConverter(CONVERTER);
    private static final WebAuthnManager WEBAUTHN = WebAuthnManager.createNonStrictWebAuthnManager(CONVERTER);
    /** The algorithms the Login UI offers at registration: ES256, then RS256. */
    private static final List<PublicKeyCredentialParameters> ALGORITHMS = List.of(
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256));

    private IdentityHandlers() {}

    public static List<Handler> create(UserStore users, WebAuthnSettings webauthn, Clock clock) {
        return List.of(
                new Resolve(users),
                new PasswordVerify(users),
                new TotpVerify(users, clock),
                new TotpEnroll(users, clock),
                new WebAuthnRegister(users, webauthn),
                new WebAuthnAssert(users, webauthn),
                new ConsentRecord());
    }

    public static String normalize(String identifier) {
        return identifier.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Fills {@code user.*} from a typed identifier, or from the user handle of a passkey chosen
     * through autofill (unverified until {@code webauthn.assert} runs).
     */
    private record Resolve(UserStore users) implements Handler {
        @Override
        public String name() {
            return "identity.resolve";
        }

        @Override
        public Set<String> amr() {
            return Set.of();
        }

        @Override
        public boolean bindsSubject() {
            return false;
        }

        @Override
        public Outcome invoke(Env env, Submission input, Map<String, Object> args) {
            Map<String, Object> out = new HashMap<>();
            Optional<User> user;
            if (input.payload().get("passkey") instanceof Map<?, ?> credential) {
                out.put("identified_by", "passkey");
                user = userHandle(credential).flatMap(users::bySubject).filter(u -> visible(u, env));
                user.ifPresent(u -> out.put("identifier", u.identifier()));
            } else {
                out.put("identified_by", "identifier");
                String typed = input.string("identifier");
                String identifier = normalize(typed == null ? "" : typed);
                out.put("identifier", identifier);
                user = users.byIdentifier(identifier).filter(u -> visible(u, env));
            }
            out.put("exists", user.isPresent());
            out.put("has_password", user.map(u -> u.passwordHash() != null).orElse(true));
            out.put("has_totp", user.map(u -> u.totpSecret() != null).orElse(false));
            out.put("has_passkey", user.map(u -> !u.passkeys().isEmpty()).orElse(false));
            out.put("mfa_required", user.map(User::mfaRequired).orElse(false));
            user.ifPresent(u -> {
                out.put("email", u.identifier());
                out.put("email_verified", u.emailVerified());
                if (u.name() != null) out.put("name", u.name());
            });
            return Outcome.user(out);
        }
    }

    private record PasswordVerify(UserStore users) implements Handler {
        @Override
        public String name() {
            return "password.verify";
        }

        @Override
        public Set<String> amr() {
            return Set.of("pwd");
        }

        @Override
        public boolean bindsSubject() {
            return true;
        }

        @Override
        public Outcome invoke(Env env, Submission input, Map<String, Object> args) {
            Optional<User> user = account(users, env);
            String password = input.string("password");
            boolean match = Passwords.matches(
                    password == null ? "" : password,
                    user.map(User::passwordHash).orElse(null));
            return match && password != null
                    ? Outcome.subject(user.get().subject())
                    : Outcome.fail("invalid_credentials");
        }
    }

    private record TotpVerify(UserStore users, Clock clock) implements Handler {
        @Override
        public String name() {
            return "totp.verify";
        }

        @Override
        public Set<String> amr() {
            return Set.of("otp");
        }

        @Override
        public boolean bindsSubject() {
            return true;
        }

        @Override
        public Outcome invoke(Env env, Submission input, Map<String, Object> args) {
            User user = account(users, env).orElse(null);
            String code = input.string("code");
            if (user == null || user.totpSecret() == null || code == null) return Outcome.fail("invalid_code");
            long step = Totp.match(
                    Base32.decode(user.totpSecret()), code, clock.instant().getEpochSecond());
            // A code is good once: the step must be newer than the last one accepted.
            AtomicBoolean fresh = new AtomicBoolean();
            if (step >= 0) {
                users.update(user.subject(), u -> {
                    if (step <= u.totpLastStep()) return u;
                    fresh.set(true);
                    return u.withTotp(u.totpSecret(), step);
                });
            }
            return fresh.get() ? Outcome.subject(user.subject()) : Outcome.fail("invalid_code");
        }
    }

    /** Stores the secret shown on {@code otp_enroll} once the user proves they hold it. Needs a signed in subject. */
    private record TotpEnroll(UserStore users, Clock clock) implements Handler {
        @Override
        public String name() {
            return "totp.enroll";
        }

        @Override
        public Set<String> amr() {
            return Set.of("otp");
        }

        @Override
        public boolean bindsSubject() {
            return true;
        }

        @Override
        public Outcome invoke(Env env, Submission input, Map<String, Object> args) {
            String subject = env.auth().subject();
            if (subject == null
                    || users.bySubject(subject).filter(u -> visible(u, env)).isEmpty()) {
                return Outcome.fail("unauthenticated");
            }
            if (!(input.context().get("secret") instanceof String secret)) {
                throw new IllegalStateException("totp.enroll must answer an otp_enroll screen");
            }
            String code = input.string("code");
            long step = code == null
                    ? -1
                    : Totp.match(Base32.decode(secret), code, clock.instant().getEpochSecond());
            if (step < 0) return Outcome.fail("invalid_code");
            users.update(subject, u -> u.withTotp(secret, step));
            return new Outcome.Ok(Map.of("has_totp", true), subject, null);
        }
    }

    /** Adds a passkey to the signed in account, or does nothing if the user chose "not now". */
    private record WebAuthnRegister(UserStore users, WebAuthnSettings settings) implements Handler {
        @Override
        public String name() {
            return "webauthn.register";
        }

        @Override
        public Set<String> amr() {
            return Set.of();
        }

        @Override
        public boolean bindsSubject() {
            return false;
        }

        @Override
        public Outcome invoke(Env env, Submission input, Map<String, Object> args) {
            String subject = env.auth().subject();
            if (subject == null
                    || users.bySubject(subject).filter(u -> visible(u, env)).isEmpty()) {
                return Outcome.fail("unauthenticated");
            }
            if (Boolean.TRUE.equals(input.payload().get("skip"))) return Outcome.ok();
            try {
                RegistrationData data = WEBAUTHN.verifyRegistrationResponseJSON(
                        credentialJson(input),
                        new RegistrationParameters(server(settings, input), ALGORITHMS, false, true));
                var attestation = data.getAttestationObject();
                var authenticatorData = attestation == null ? null : attestation.getAuthenticatorData();
                AttestedCredentialData credential =
                        authenticatorData == null ? null : authenticatorData.getAttestedCredentialData();
                if (authenticatorData == null || credential == null) return Outcome.fail("invalid_credentials");
                var passkey = new User.Passkey(
                        base64url(credential.getCredentialId()),
                        CREDENTIAL_DATA.convert(credential),
                        authenticatorData.getSignCount());
                users.update(subject, u -> {
                    List<User.Passkey> all = new ArrayList<>(u.passkeys());
                    all.add(passkey);
                    return u.withPasskeys(all);
                });
                return Outcome.user(Map.of("has_passkey", true));
            } catch (DataConversionException | VerificationException | IllegalArgumentException e) {
                return Outcome.fail("invalid_credentials");
            }
        }
    }

    /** Proves {@code hwk}, plus {@code user} when the authenticator verified the user. */
    private record WebAuthnAssert(UserStore users, WebAuthnSettings settings) implements Handler {
        @Override
        public String name() {
            return "webauthn.assert";
        }

        @Override
        public Set<String> amr() {
            return Set.of("hwk", "user");
        }

        @Override
        public boolean bindsSubject() {
            return true;
        }

        @Override
        public Outcome invoke(Env env, Submission input, Map<String, Object> args) {
            try {
                AuthenticationData data = WEBAUTHN.parseAuthenticationResponseJSON(credentialJson(input));
                Optional<User> account = account(users, env);
                var parsedId = data.getCredentialId();
                if (account.isEmpty() || parsedId == null) return Outcome.fail("invalid_credentials");
                // A copy: webauthn4j marks the array type itself nullable, which NullAway cannot narrow
                byte[] credentialId = Arrays.copyOf(parsedId, parsedId.length);
                User user = account.get();
                String id = base64url(credentialId);
                User.Passkey passkey = user.passkeys().stream()
                        .filter(p -> p.credentialId().equals(id))
                        .findFirst()
                        .orElse(null);
                var handle = data.getUserHandle();
                if (passkey == null
                        || (handle != null
                                && !Arrays.equals(handle, user.subject().getBytes(StandardCharsets.UTF_8)))) {
                    return Outcome.fail("invalid_credentials");
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
                        data,
                        new AuthenticationParameters(
                                server(settings, input), record, List.of(credentialId), false, true));
                var authenticatorData = data.getAuthenticatorData();
                if (authenticatorData == null) return Outcome.fail("invalid_credentials");
                long signCount = authenticatorData.getSignCount();
                users.update(
                        user.subject(),
                        u -> u.withPasskeys(u.passkeys().stream()
                                .map(p -> p.credentialId().equals(id)
                                        ? new User.Passkey(id, p.attestedCredentialData(), signCount)
                                        : p)
                                .toList()));
                boolean verified = authenticatorData.isFlagUV();
                return new Outcome.Ok(Map.of(), user.subject(), verified ? Set.of("hwk", "user") : Set.of("hwk"));
            } catch (DataConversionException | VerificationException | IllegalArgumentException e) {
                return Outcome.fail("invalid_credentials");
            }
        }
    }

    /** The consent screen's answer: allow continues, deny fails the section. */
    private record ConsentRecord() implements Handler {
        @Override
        public String name() {
            return "consent.record";
        }

        @Override
        public Set<String> amr() {
            return Set.of();
        }

        @Override
        public boolean bindsSubject() {
            return false;
        }

        @Override
        public Outcome invoke(Env env, Submission input, Map<String, Object> args) {
            return "allow".equals(input.string("decision")) ? Outcome.ok() : Outcome.fail("consent_denied");
        }
    }

    // ---- helpers ----

    /** The signed in account if a subject is bound, else the one {@code identity.resolve} found. */
    private static Optional<User> account(UserStore users, Env env) {
        Optional<User> user = env.auth().subject() != null
                ? users.bySubject(env.auth().subject())
                : env.user().get("identifier") instanceof String id ? users.byIdentifier(id) : Optional.empty();
        return user.filter(u -> visible(u, env));
    }

    /** A preview test account exists only for preview clients. */
    private static boolean visible(User user, Env env) {
        return !user.preview() || Previews.isPreview(env.client());
    }

    /** The subject a passkey response names; user handles are the subject's UTF-8 bytes. */
    private static Optional<String> userHandle(Map<?, ?> credential) {
        if (credential.get("response") instanceof Map<?, ?> response
                && response.get("userHandle") instanceof String handle) {
            try {
                return Optional.of(new String(Base64.getUrlDecoder().decode(handle), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** The browser's PublicKeyCredential JSON, as posted. */
    private static String credentialJson(Submission input) {
        if (!(input.payload().get("passkey") instanceof Map<?, ?> credential)) {
            throw new IllegalArgumentException("no passkey in submission");
        }
        try {
            return JSON.writeValueAsString(credential);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** Origin, RP id and the challenge the answered screen issued. */
    private static ServerProperty server(WebAuthnSettings settings, Submission input) {
        if (!(input.context().get("challenge") instanceof String challenge)) {
            throw new IllegalArgumentException("the answered screen issued no challenge");
        }
        return ServerProperty.builder()
                .origins(settings.origins().stream().map(Origin::new).collect(Collectors.toSet()))
                .rpId(settings.rpId())
                .challenge(new DefaultChallenge(Base64.getUrlDecoder().decode(challenge)))
                .build();
    }

    private static String base64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
