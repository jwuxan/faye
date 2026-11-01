package faye.handlers;

import faye.engine.CredentialIssuer;
import faye.engine.Env;
import faye.engine.spi.OtpEnrollment;
import faye.engine.spi.OtpIssuer;
import faye.engine.spi.PasskeyAuthenticator;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link CredentialIssuer}s of the built in handlers, one per screen that shows credential
 * material: WebAuthn challenges, which {@code webauthn.assert} and {@code webauthn.register} verify,
 * and the TOTP secret {@code totp.enroll} verifies, which survives a retry. Registered with {@link
 * BuiltinHandlers#create}'s handlers, so what is issued is what they check (ADR 0025).
 */
public final class BuiltinIssuers {

    /** Shown in authenticator apps when neither the tenant nor the client has a name. */
    static final String FALLBACK_ISSUER = "Sign in";

    private BuiltinIssuers() {}

    public static List<CredentialIssuer> create(OtpIssuer otp, PasskeyAuthenticator passkeys) {
        return List.of(new Identifier(passkeys), new Passkey(passkeys), new OtpEnroll(otp));
    }

    /** A challenge for passkey autofill, when the identifier screen offers it. */
    private record Identifier(PasskeyAuthenticator passkeys) implements CredentialIssuer {
        @Override
        public String screen() {
            return "identifier";
        }

        @Override
        public Map<String, Object> issue(Env env, Map<String, Object> opts, Map<String, Object> previous) {
            return Boolean.TRUE.equals(opts.get("passkey")) ? Map.of("challenge", passkeys.challenge()) : Map.of();
        }

        @Override
        public Map<String, Object> present(Env env, Map<String, Object> issued) {
            return issued.get("challenge") instanceof String c ? Map.of("passkey", Map.of("challenge", c)) : Map.of();
        }
    }

    /** A challenge to sign with a passkey, or to register one for the signed in subject. */
    private record Passkey(PasskeyAuthenticator passkeys) implements CredentialIssuer {
        @Override
        public String screen() {
            return "passkey";
        }

        @Override
        public Map<String, Object> issue(Env env, Map<String, Object> opts, Map<String, Object> previous) {
            if (Boolean.TRUE.equals(opts.get("register")) && env.auth().subject() == null) {
                throw new IllegalStateException("passkey registration needs a signed in subject");
            }
            return Map.of("challenge", passkeys.challenge());
        }

        /** A challenge, and a registration only for the signed in subject. */
        @Override
        public boolean resumable(Env env, Map<String, Object> issued) {
            return issued.get("challenge") instanceof String
                    && (!Boolean.TRUE.equals(issued.get("register"))
                            || env.auth().subject() != null);
        }

        @Override
        public Map<String, Object> present(Env env, Map<String, Object> issued) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("challenge", issued.get("challenge"));
            String subject = env.auth().subject();
            if (Boolean.TRUE.equals(issued.get("register")) && subject != null) {
                String name = env.user().get("identifier") instanceof String s ? s : subject;
                out.put(
                        "register",
                        Map.of(
                                "userId",
                                Base64.getUrlEncoder()
                                        .withoutPadding()
                                        .encodeToString(subject.getBytes(StandardCharsets.UTF_8)),
                                "userName",
                                name,
                                "displayName",
                                env.user().get("name") instanceof String n ? n : name,
                                "algorithms",
                                passkeys.algorithms()));
            }
            return out;
        }
    }

    /** A TOTP secret to enroll, labeled with the tenant's display name in authenticator apps. */
    private record OtpEnroll(OtpIssuer otp) implements CredentialIssuer {
        @Override
        public String screen() {
            return "otp_enroll";
        }

        @Override
        public Map<String, Object> issue(Env env, Map<String, Object> opts, Map<String, Object> previous) {
            // The issued secret and its provisioning URI survive a retry
            if (previous.get("secret") instanceof String s && previous.get("uri") instanceof String u) {
                return Map.of("secret", s, "uri", u);
            }
            String subject = env.auth().subject();
            if (subject == null) {
                throw new IllegalStateException("otp enrollment needs a signed in subject");
            }
            String account = env.user().get("identifier") instanceof String s ? s : "account";
            OtpEnrollment issued = otp.issue(subject, account, issuer(env));
            return Map.of("secret", issued.secret(), "uri", issued.uri());
        }

        @Override
        public boolean resumable(Env env, Map<String, Object> issued) {
            return issued.get("secret") instanceof String;
        }

        @Override
        public Map<String, Object> present(Env env, Map<String, Object> issued) {
            String secret = (String) issued.get("secret");
            // A state saved before the issuer precomputed the URI shows the secret alone
            return issued.get("uri") instanceof String uri
                    ? Map.of("secret", secret, "uri", uri)
                    : Map.of("secret", secret);
        }
    }

    /** The tenant's display name, else the client's name, else a generic label. */
    static String issuer(Env env) {
        if (env.tenant().get("name") instanceof String n && !n.isBlank()) return n;
        if (env.client().get("client_name") instanceof String n && !n.isBlank()) return n;
        return FALLBACK_ISSUER;
    }
}
