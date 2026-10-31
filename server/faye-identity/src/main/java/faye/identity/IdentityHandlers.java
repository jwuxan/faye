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
import faye.engine.Effect;
import faye.engine.Engine;
import faye.engine.Env;
import faye.engine.Handler;
import faye.engine.HandlerMeta;
import faye.engine.HandlerMeta.EffectKind;
import faye.engine.Invocation;
import faye.engine.Outcome;
import faye.engine.Previews;
import faye.engine.Submission;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

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
 *
 * <p>Writes follow the effect classes of ADR 0024: {@code totp.verify} uses a TOTP step and {@code
 * webauthn.assert} raises a sign count inline, as part of verifying ({@code guard}); {@code
 * totp.enroll} and {@code webauthn.register} return {@link Effect}s the engine commits once the
 * flow state is saved ({@code write}). Each handler's {@link HandlerMeta} equals its entry in
 * {@code conformance/handlers.yaml}.
 */
public final class IdentityHandlers {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectConverter CONVERTER = new ObjectConverter();
    private static final AttestedCredentialDataConverter CREDENTIAL_DATA =
            new AttestedCredentialDataConverter(CONVERTER);
    private static final WebAuthnManager WEBAUTHN = WebAuthnManager.createNonStrictWebAuthnManager(CONVERTER);
    /** The algorithms the Login UI offers at registration, from the one list the issuer keeps. */
    private static final List<PublicKeyCredentialParameters> ALGORITHMS = IdentityIssuers.PASSKEY_ALGORITHMS.stream()
            .map(alg -> new PublicKeyCredentialParameters(
                    PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.create(alg)))
            .toList();

    private static HandlerMeta declared(
            Set<String> amr, boolean binds, boolean identifies, boolean requiresSubject, EffectKind effect) {
        return new HandlerMeta(amr, binds, identifies, requiresSubject, effect, HandlerMeta.NO_ARGS);
    }

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
        public HandlerMeta meta() {
            return declared(Set.of(), false, true, false, EffectKind.NONE);
        }

        @Override
        public Outcome invoke(Invocation call) {
            Env env = call.env();
            Submission input = call.input();
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
        public HandlerMeta meta() {
            return declared(Set.of("pwd"), true, false, false, EffectKind.NONE);
        }

        @Override
        public Outcome invoke(Invocation call) {
            Env env = call.env();
            Submission input = call.input();
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
        public HandlerMeta meta() {
            return declared(Set.of("otp"), true, false, false, EffectKind.GUARD);
        }

        @Override
        public Outcome invoke(Invocation call) {
            Env env = call.env();
            Submission input = call.input();
            User user = account(users, env).orElse(null);
            String code = input.string("code");
            if (user == null || user.totpSecret() == null || code == null) return Outcome.fail("invalid_code");
            long step = Totp.match(
                    Base32.decode(user.totpSecret()), code, clock.instant().getEpochSecond());
            // A code is good once (RFC 6238 section 5.2): using its step is part of verifying it, and
            // only this flow may use the same step again (a double submit of one screen).
            return step >= 0 && users.useTotpStep(user.subject(), step, call.flowId())
                    ? Outcome.subject(user.subject())
                    : Outcome.fail("invalid_code");
        }
    }

    /** Stores the secret shown on {@code otp_enroll} once the user proves they hold it. Needs a signed in subject. */
    private record TotpEnroll(UserStore users, Clock clock) implements Handler {
        @Override
        public String name() {
            return "totp.enroll";
        }

        @Override
        public HandlerMeta meta() {
            return declared(Set.of("otp"), true, false, true, EffectKind.WRITE);
        }

        @Override
        public Outcome invoke(Invocation call) {
            Env env = call.env();
            Submission input = call.input();
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
            Effect enroll = new Effect(
                    name(), Map.of("subject", subject, "secret", secret, "step", step, "flow", call.flowId()));
            return new Outcome.Ok(Map.of("has_totp", true), subject, null, List.of(enroll));
        }

        /** Idempotent: enrolling the same secret again writes the same row. */
        @Override
        public @Nullable String commit(Effect effect) {
            Map<String, Object> d = effect.data();
            users.enrollTotp(text(d, "subject"), text(d, "secret"), number(d, "step"), text(d, "flow"));
            return null;
        }
    }

    /** Adds a passkey to the signed in account, or does nothing if the user chose "not now". */
    private record WebAuthnRegister(UserStore users, WebAuthnSettings settings) implements Handler {
        @Override
        public String name() {
            return "webauthn.register";
        }

        @Override
        public HandlerMeta meta() {
            return declared(Set.of(), false, false, true, EffectKind.WRITE);
        }

        @Override
        public Outcome invoke(Invocation call) {
            Env env = call.env();
            Submission input = call.input();
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
                Effect register = new Effect(
                        name(),
                        Map.of(
                                "subject",
                                subject,
                                "credentialId",
                                base64url(credential.getCredentialId()),
                                "attestedCredentialData",
                                base64url(CREDENTIAL_DATA.convert(credential)),
                                "signCount",
                                authenticatorData.getSignCount()));
                return new Outcome.Ok(Map.of("has_passkey", true), null, null, List.of(register));
            } catch (DataConversionException | VerificationException | IllegalArgumentException e) {
                return Outcome.fail("invalid_credentials");
            }
        }

        /**
         * Adds the passkey. Idempotent: the account already having this credential id is success;
         * another account having it fails the flow.
         */
        @Override
        public @Nullable String commit(Effect effect) {
            Map<String, Object> d = effect.data();
            var passkey = new User.Passkey(
                    text(d, "credentialId"),
                    Base64.getUrlDecoder().decode(text(d, "attestedCredentialData")),
                    number(d, "signCount"));
            return users.addPasskey(text(d, "subject"), passkey) ? null : "invalid_credentials";
        }
    }

    /** Proves {@code hwk}, plus {@code user} when the authenticator verified the user. */
    private record WebAuthnAssert(UserStore users, WebAuthnSettings settings) implements Handler {
        @Override
        public String name() {
            return "webauthn.assert";
        }

        @Override
        public HandlerMeta meta() {
            return declared(Set.of("hwk", "user"), true, false, false, EffectKind.GUARD);
        }

        @Override
        public Outcome invoke(Invocation call) {
            Env env = call.env();
            Submission input = call.input();
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
                // A monotonic maximum: a count that did not rise is a risk signal, not a failure
                // (WebAuthn Level 3, section 6.1.1); webauthn4j has compared it to the stored one
                users.raiseSignCount(user.subject(), id, authenticatorData.getSignCount());
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
        public HandlerMeta meta() {
            return declared(Set.of(), false, false, false, EffectKind.NONE);
        }

        @Override
        public Outcome invoke(Invocation call) {
            Env env = call.env();
            Submission input = call.input();
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

    /**
     * A preview test account exists only for preview clients, and an account of one tenant only for
     * that tenant's clients (a client without {@code metadata.tenant} runs the default tenant).
     */
    private static boolean visible(User user, Env env) {
        return (!user.preview() || Previews.isPreview(env.client()))
                && (user.tenant() == null || user.tenant().equals(tenantOf(env.client())));
    }

    private static String tenantOf(Map<String, Object> client) {
        return client.get("metadata") instanceof Map<?, ?> meta && meta.get("tenant") instanceof String t
                ? t
                : Engine.DEFAULT_TENANT;
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

    /** A string field of an effect's data, which this module wrote. */
    private static String text(Map<String, Object> data, String key) {
        if (data.get(key) instanceof String v) return v;
        throw new IllegalStateException("effect data without " + key);
    }

    /** A number field of an effect's data; JSON may hand it back as an Integer or a Long. */
    private static long number(Map<String, Object> data, String key) {
        if (data.get(key) instanceof Number v) return v.longValue();
        throw new IllegalStateException("effect data without " + key);
    }

    private static String base64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
