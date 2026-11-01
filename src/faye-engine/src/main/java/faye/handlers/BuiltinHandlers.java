package faye.handlers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import faye.engine.Effect;
import faye.engine.Env;
import faye.engine.Handler;
import faye.engine.HandlerMeta;
import faye.engine.HandlerMeta.EffectKind;
import faye.engine.Invocation;
import faye.engine.Outcome;
import faye.engine.Submission;
import faye.engine.spi.Account;
import faye.engine.spi.OtpVerifier;
import faye.engine.spi.PasskeyAssertion;
import faye.engine.spi.PasskeyAuthenticator;
import faye.engine.spi.PasskeyCredential;
import faye.engine.spi.PasswordAuthenticator;
import faye.engine.spi.UserDirectory;
import faye.ir.Entry;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The built in handlers: {@code identity.resolve}, {@code password.verify}, {@code totp.verify},
 * {@code totp.enroll}, {@code webauthn.register}, {@code webauthn.assert} and {@code consent.record}.
 * They know accounts and credentials only through the SPIs of {@code faye.engine.spi}; a host ships
 * the implementation behind them (ADR 0025, ADR 0024).
 *
 * <p>Enumeration safety: an unknown identifier resolves like a known account with only a password,
 * password checks for it cost one verification like any other, and every credential failure is the
 * same {@code invalid_credentials} (or {@code invalid_code} for one time codes).
 *
 * <p>Preview test accounts ({@link Account#preview()}) exist only for preview clients: for any other
 * client every handler treats them as unknown.
 *
 * <p>Writes follow the effect classes of ADR 0024: {@code totp.verify} uses a TOTP step and {@code
 * webauthn.assert} raises a sign count inline, as part of verifying ({@code guard}); {@code
 * totp.enroll} and {@code webauthn.register} return {@link Effect}s the engine commits once the
 * flow state is saved ({@code write}). Each handler's {@link HandlerMeta} equals its entry in
 * {@code conformance/handlers.yaml}.
 */
public final class BuiltinHandlers {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static HandlerMeta declared(
            Set<String> amr, boolean binds, boolean identifies, boolean requiresSubject, EffectKind effect) {
        return new HandlerMeta(amr, binds, identifies, requiresSubject, effect, HandlerMeta.NO_ARGS);
    }

    private BuiltinHandlers() {}

    public static List<Handler> create(
            UserDirectory users, PasswordAuthenticator passwords, OtpVerifier otp, PasskeyAuthenticator passkeys) {
        return List.of(
                new Resolve(users),
                new PasswordVerify(users, passwords),
                new TotpVerify(users, otp),
                new TotpEnroll(users, otp),
                new WebAuthnRegister(users, passkeys),
                new WebAuthnAssert(users, passkeys),
                new ConsentRecord());
    }

    /** How the engine normalizes a typed identifier: trim, then lowercase. */
    public static String normalize(String identifier) {
        return UserDirectory.normalize(identifier);
    }

    /**
     * Fills {@code user.*} from a typed identifier, or from the user handle of a passkey chosen
     * through autofill (unverified until {@code webauthn.assert} runs).
     */
    private record Resolve(UserDirectory users) implements Handler {
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
            Optional<Account> user;
            if (input.payload().get("passkey") instanceof Map<?, ?> credential) {
                out.put("identified_by", "passkey");
                user = userHandle(credential).flatMap(users::bySubject).filter(u -> visible(u, call));
                user.ifPresent(u -> out.put("identifier", u.identifier()));
            } else {
                out.put("identified_by", "identifier");
                String typed = input.string("identifier");
                String identifier = normalize(typed == null ? "" : typed);
                out.put("identifier", identifier);
                user = users.byIdentifier(identifier).filter(u -> visible(u, call));
            }
            // A step up is for the subject the host already knows: identifying anyone else would
            // put their attributes beside that subject's credentials (and so in its claims)
            String bound = env.auth().subject();
            if (bound != null && !user.map(u -> u.subject().equals(bound)).orElse(false)) {
                return Outcome.fail("wrong_account");
            }
            out.put("exists", user.isPresent());
            out.put("has_password", user.map(Account::hasPassword).orElse(true));
            out.put("has_totp", user.map(Account::hasTotp).orElse(false));
            out.put("has_passkey", user.map(Account::hasPasskey).orElse(false));
            // The person's switch: a verified phone with phone codes on; the number never enters user.*
            out.put("has_phone", user.map(Account::hasPhone).orElse(false));
            out.put("mfa_required", user.map(Account::mfaRequired).orElse(false));
            user.ifPresent(u -> {
                out.put("email", u.identifier());
                out.put("email_verified", u.emailVerified());
                if (u.name() != null) out.put("name", u.name());
            });
            return Outcome.user(out);
        }
    }

    private record PasswordVerify(UserDirectory users, PasswordAuthenticator passwords) implements Handler {
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
            Optional<Account> user = account(users, call);
            String password = input.string("password");
            String identifier =
                    user.map(Account::identifier).orElse(env.user().get("identifier") instanceof String id ? id : "");
            boolean match = passwords.verify(user.orElse(null), identifier, password == null ? "" : password);
            return match && password != null
                    ? Outcome.subject(user.get().subject())
                    : Outcome.fail("invalid_credentials");
        }
    }

    private record TotpVerify(UserDirectory users, OtpVerifier otp) implements Handler {
        @Override
        public String name() {
            return "totp.verify";
        }

        @Override
        public HandlerMeta meta() {
            // A second factor only: a code from an app is never the one credential (ADR 0032)
            return declared(Set.of("otp"), true, false, true, EffectKind.GUARD);
        }

        @Override
        public Outcome invoke(Invocation call) {
            Submission input = call.input();
            if (call.env().auth().subject() == null) return Outcome.fail("unauthenticated");
            Account user = account(users, call).orElse(null);
            String code = input.string("code");
            // A code is good once (RFC 6238 section 5.2): using its step is part of verifying it, and
            // only this flow may use the same step again (a double submit of one screen).
            return user != null && code != null && otp.verify(user.subject(), code, call.flowId())
                    ? Outcome.subject(user.subject())
                    : Outcome.fail("invalid_code");
        }
    }

    /** Stores the secret shown on {@code otp_enroll} once the user proves they hold it. Needs a signed in subject. */
    private record TotpEnroll(UserDirectory users, OtpVerifier otp) implements Handler {
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
                    || users.bySubject(subject).filter(u -> visible(u, call)).isEmpty()) {
                return Outcome.fail("unauthenticated");
            }
            if (!(input.context().get("secret") instanceof String secret)) {
                throw new IllegalStateException("totp.enroll must answer an otp_enroll screen");
            }
            String code = input.string("code");
            var step = code == null ? OptionalLong.empty() : otp.checkEnrollment(secret, code);
            if (step.isEmpty()) return Outcome.fail("invalid_code");
            Effect enroll = new Effect(
                    name(),
                    Map.of("subject", subject, "secret", secret, "step", step.getAsLong(), "flow", call.flowId()));
            return new Outcome.Ok(Map.of("has_totp", true), subject, null, List.of(enroll));
        }

        /** Idempotent: enrolling the same secret again writes the same row. */
        @Override
        public @Nullable String commit(Effect effect) {
            Map<String, Object> d = effect.data();
            otp.enroll(text(d, "subject"), text(d, "secret"), number(d, "step"), text(d, "flow"));
            return null;
        }
    }

    /** Adds a passkey to the signed in account, or does nothing if the user chose "not now". */
    private record WebAuthnRegister(UserDirectory users, PasskeyAuthenticator passkeys) implements Handler {
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
                    || users.bySubject(subject).filter(u -> visible(u, call)).isEmpty()) {
                return Outcome.fail("unauthenticated");
            }
            if (Boolean.TRUE.equals(input.payload().get("skip"))) return Outcome.ok();
            Optional<PasskeyCredential> credential;
            try {
                credential = passkeys.verifyRegistration(subject, credentialJson(input), challenge(input));
            } catch (IllegalArgumentException e) {
                // No passkey posted, or the answered screen issued no challenge
                return Outcome.fail("invalid_credentials");
            }
            if (credential.isEmpty()) return Outcome.fail("invalid_credentials");
            PasskeyCredential c = credential.get();
            Effect register =
                    new Effect(name(), Map.of("subject", subject, "credentialId", c.credentialId(), "data", c.data()));
            return new Outcome.Ok(Map.of("has_passkey", true), null, null, List.of(register));
        }

        /**
         * Adds the passkey. Idempotent: the account already having this credential id is success;
         * another account having it fails the flow.
         */
        @Override
        public @Nullable String commit(Effect effect) {
            Map<String, Object> d = effect.data();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = d.get("data") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            var credential = new PasskeyCredential(text(d, "credentialId"), data);
            return passkeys.register(text(d, "subject"), credential) ? null : "invalid_credentials";
        }
    }

    /** Proves {@code hwk}, plus {@code user} when the authenticator verified its holder. */
    private record WebAuthnAssert(UserDirectory directory, PasskeyAuthenticator passkeys) implements Handler {
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
            Submission input = call.input();
            Optional<Account> account = account(directory, call);
            if (account.isEmpty()) return Outcome.fail("invalid_credentials");
            Account user = account.get();
            // Defense in depth: an assertion that names a different subject never reaches the adapter
            if (input.payload().get("passkey") instanceof Map<?, ?> credential
                    && userHandle(credential)
                            .map(h -> !h.equals(user.subject()))
                            .orElse(false)) {
                return Outcome.fail("invalid_credentials");
            }
            Optional<PasskeyAssertion> assertion;
            try {
                assertion = passkeys.verifyAssertion(user.subject(), credentialJson(input), challenge(input));
            } catch (IllegalArgumentException e) {
                // No passkey posted, or the answered screen issued no challenge
                return Outcome.fail("invalid_credentials");
            }
            if (assertion.isEmpty()) return Outcome.fail("invalid_credentials");
            boolean verified = assertion.get().userVerified();
            return new Outcome.Ok(Map.of(), user.subject(), verified ? Set.of("hwk", "user") : Set.of("hwk"));
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
            // It records a grant: it belongs in consent entries only (ADR 0026)
            return new HandlerMeta(
                    Set.of(), false, false, false, EffectKind.NONE, HandlerMeta.NO_ARGS, Set.of(Entry.Kind.CONSENT));
        }

        @Override
        public Outcome invoke(Invocation call) {
            Submission input = call.input();
            return "allow".equals(input.string("decision")) ? Outcome.ok() : Outcome.fail("consent_denied");
        }
    }

    // ---- helpers ----

    /** The signed in account if a subject is bound, else the one {@code identity.resolve} found. */
    private static Optional<Account> account(UserDirectory users, Invocation call) {
        Env env = call.env();
        Optional<Account> user = env.auth().subject() != null
                ? users.bySubject(env.auth().subject())
                : env.user().get("identifier") instanceof String id ? users.byIdentifier(id) : Optional.empty();
        return user.filter(u -> visible(u, call));
    }

    /**
     * A preview test account exists only for preview flows, and an account of one tenant only for
     * that tenant's flows; the host decides both for each request.
     */
    private static boolean visible(Account user, Invocation call) {
        return (!user.preview() || call.preview())
                && (user.tenant() == null || user.tenant().equals(call.tenant()));
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

    /** The challenge the answered screen issued. */
    private static String challenge(Submission input) {
        if (!(input.context().get("challenge") instanceof String challenge)) {
            throw new IllegalArgumentException("the answered screen issued no challenge");
        }
        return challenge;
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
}
