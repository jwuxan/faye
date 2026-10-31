package faye.identity;

import faye.engine.CredentialIssuer;
import faye.engine.Env;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link CredentialIssuer}s of this module, one per screen that shows credential material:
 * random WebAuthn challenges, which {@code webauthn.assert} and {@code webauthn.register} verify,
 * and the TOTP secret {@code totp.enroll} verifies, which survives a retry. Registered with {@link
 * IdentityHandlers#create}'s handlers, so what is issued is what they check (ADR 0025).
 */
public final class IdentityIssuers {

    /**
     * The COSE algorithm ids {@code webauthn.register} verifies, in order of preference: ES256, then
     * RS256. The passkey screen's {@code register} carries them so the Login UI offers exactly what
     * the server accepts (ADR 0022).
     */
    public static final List<Long> PASSKEY_ALGORITHMS = List.of(-7L, -257L);

    /** Shown in authenticator apps when neither the tenant nor the client has a name. */
    static final String FALLBACK_ISSUER = "Sign in";

    private static final SecureRandom RANDOM = new SecureRandom();

    private IdentityIssuers() {}

    public static List<CredentialIssuer> create() {
        return List.of(new Identifier(), new Passkey(), new OtpEnroll());
    }

    /** A challenge for passkey autofill, when the identifier screen offers it. */
    private record Identifier() implements CredentialIssuer {
        @Override
        public String screen() {
            return "identifier";
        }

        @Override
        public Map<String, Object> issue(Env env, Map<String, Object> opts, Map<String, Object> previous) {
            return Boolean.TRUE.equals(opts.get("passkey")) ? Map.of("challenge", random(32)) : Map.of();
        }

        @Override
        public Map<String, Object> present(Env env, Map<String, Object> issued) {
            return issued.get("challenge") instanceof String c ? Map.of("passkey", Map.of("challenge", c)) : Map.of();
        }
    }

    /** A challenge to sign with a passkey, or to register one for the signed in subject. */
    private record Passkey() implements CredentialIssuer {
        @Override
        public String screen() {
            return "passkey";
        }

        @Override
        public Map<String, Object> issue(Env env, Map<String, Object> opts, Map<String, Object> previous) {
            if (Boolean.TRUE.equals(opts.get("register")) && env.auth().subject() == null) {
                throw new IllegalStateException("passkey registration needs a signed in subject");
            }
            return Map.of("challenge", random(32));
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
                                PASSKEY_ALGORITHMS));
            }
            return out;
        }
    }

    /** A TOTP secret to enroll, labeled with the tenant's display name in authenticator apps. */
    private record OtpEnroll() implements CredentialIssuer {
        @Override
        public String screen() {
            return "otp_enroll";
        }

        @Override
        public Map<String, Object> issue(Env env, Map<String, Object> opts, Map<String, Object> previous) {
            if (previous.get("secret") instanceof String s) return Map.of("secret", s);
            byte[] secret = new byte[20];
            RANDOM.nextBytes(secret);
            return Map.of("secret", Base32.encode(secret));
        }

        @Override
        public boolean resumable(Env env, Map<String, Object> issued) {
            return issued.get("secret") instanceof String;
        }

        @Override
        public Map<String, Object> present(Env env, Map<String, Object> issued) {
            String secret = (String) issued.get("secret");
            String issuer = issuer(env);
            String account = env.user().get("identifier") instanceof String s ? s : "account";
            return Map.of(
                    "secret",
                    secret,
                    "uri",
                    "otpauth://totp/" + encode(issuer + ":" + account) + "?secret=" + secret + "&issuer="
                            + encode(issuer));
        }
    }

    /** The tenant's display name, else the client's name, else a generic label. */
    static String issuer(Env env) {
        if (env.tenant().get("name") instanceof String n && !n.isBlank()) return n;
        if (env.client().get("client_name") instanceof String n && !n.isBlank()) return n;
        return FALLBACK_ISSUER;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String random(int bytes) {
        byte[] out = new byte[bytes];
        RANDOM.nextBytes(out);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
    }
}
