package faye.engine;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The screens of {@code schema/screen.schema.json}, which the Login UI renders. {@link
 * ScreenRegistry#builtIn()} registers all of them; {@code ScreenRegistryTest} keeps the two lists
 * equal.
 */
public enum BuiltinScreen implements ScreenSpec {
    IDENTIFIER("identifier"),
    PASSWORD("password"),
    METHOD_PICKER("method_picker"),
    PASSKEY("passkey"),
    OTP("otp"),
    OTP_ENROLL("otp_enroll"),
    CONSENT("consent"),
    ERROR("error");

    private static final SecureRandom RANDOM = new SecureRandom();
    /**
     * The COSE algorithm ids {@code webauthn.register} verifies, in order of preference: ES256,
     * then RS256. The passkey screen's {@code register} carries them so the Login UI offers exactly
     * what the server accepts.
     */
    public static final List<Long> PASSKEY_ALGORITHMS = List.of(-7L, -257L);
    /** Account label prefix in authenticator apps. */
    private static final String ISSUER = "Faye";

    private final String wireName;

    BuiltinScreen(String wireName) {
        this.wireName = wireName;
    }

    @Override
    public String type() {
        return wireName;
    }

    @Override
    public Set<String> options() {
        return switch (this) {
            case IDENTIFIER -> Set.of("passkey");
            case PASSKEY -> Set.of("register");
            case ERROR -> Set.of("code");
            case PASSWORD, METHOD_PICKER, OTP, OTP_ENROLL, CONSENT -> Set.of();
        };
    }

    /**
     * Options: {@code passkey} (identifier: offer passkey autofill), {@code register} (passkey:
     * create rather than use one), {@code code} (error).
     */
    @Override
    public Map<String, Object> prepare(Env env, Map<String, Object> opts, Map<String, Object> previous) {
        Map<String, Object> data = new LinkedHashMap<>();
        switch (this) {
            case IDENTIFIER -> {
                if (Boolean.TRUE.equals(opts.get("passkey"))) data.put("challenge", random(32));
            }
            case PASSKEY -> {
                data.put("challenge", random(32));
                if (Boolean.TRUE.equals(opts.get("register"))) {
                    if (env.auth().subject() == null) {
                        throw new IllegalStateException("passkey registration needs a signed in subject");
                    }
                    data.put("register", true);
                }
            }
            case OTP_ENROLL -> {
                byte[] secret = new byte[20];
                RANDOM.nextBytes(secret);
                data.put("secret", previous.get("secret") instanceof String s ? s : Base32.encode(secret));
            }
            case ERROR -> data.put("code", opts.get("code") instanceof String c ? c : "error");
            case PASSWORD, METHOD_PICKER, OTP, CONSENT -> {}
        }
        return data;
    }

    @Override
    public Map<String, Object> render(Env env, Map<String, Object> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", wireName);
        Object identifier =
                this == IDENTIFIER ? env.host().get("login_hint") : env.user().get("identifier");
        if (identifier instanceof String s) out.put("identifier", s);
        switch (this) {
            case IDENTIFIER -> {
                if (data.get("challenge") != null) out.put("passkey", Map.of("challenge", data.get("challenge")));
            }
            case METHOD_PICKER -> out.put("options", data.get("options"));
            case PASSKEY -> {
                out.put("challenge", data.get("challenge"));
                if (Boolean.TRUE.equals(data.get("register"))) {
                    String subject = Objects.requireNonNull(env.auth().subject(), "prepare checked the subject");
                    String name = identifier instanceof String s ? s : subject;
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
            }
            case OTP_ENROLL -> {
                String secret = (String) data.get("secret");
                String label = ISSUER + ":" + (identifier instanceof String s ? s : "account");
                out.put("secret", secret);
                out.put(
                        "uri",
                        "otpauth://totp/"
                                + URLEncoder.encode(label, StandardCharsets.UTF_8)
                                        .replace("+", "%20") + "?secret=" + secret + "&issuer=" + ISSUER);
            }
            case CONSENT -> {
                Map<String, Object> client = new LinkedHashMap<>();
                client.put("id", env.client().getOrDefault("client_id", ""));
                if (env.client().get("client_name") instanceof String n && !n.isEmpty()) client.put("name", n);
                out.put("client", client);
                out.put("scopes", env.host().get("requested_scope") instanceof List<?> l ? l : List.of());
            }
            case ERROR -> out.put("code", data.get("code"));
            case PASSWORD, OTP -> {}
        }
        return out;
    }

    @Override
    public boolean accepts(Submission input, Map<String, Object> data) {
        Map<String, Object> p = input.payload();
        return switch (this) {
            case IDENTIFIER -> text(p, "identifier", 256) || (data.containsKey("challenge") && credential(p));
            case PASSWORD -> text(p, "password", 1024);
            case METHOD_PICKER ->
                p.size() == 1 && data.get("options") instanceof List<?> l && l.contains(p.get("method"));
            case PASSKEY ->
                credential(p)
                        || (Boolean.TRUE.equals(data.get("register"))
                                && p.size() == 1
                                && Boolean.TRUE.equals(p.get("skip")));
            case OTP, OTP_ENROLL -> p.size() == 1 && p.get("code") instanceof String c && c.matches("[0-9]{6}");
            case CONSENT -> p.size() == 1 && ("allow".equals(p.get("decision")) || "deny".equals(p.get("decision")));
            case ERROR -> p.isEmpty();
        };
    }

    /** A passkey chosen from autofill on the identifier screen also answers the passkey screen. */
    @Override
    public Optional<String> hosted(Submission input) {
        return this == IDENTIFIER && input.payload().containsKey("passkey")
                ? Optional.of(PASSKEY.wireName)
                : Optional.empty();
    }

    /** A passkey screen that uses a passkey, not one that registers one. */
    @Override
    public boolean answeredBy(String hosted, Map<String, Object> opts) {
        return wireName.equals(hosted) && !Boolean.TRUE.equals(opts.get("register"));
    }

    private static boolean text(Map<String, Object> p, String field, int maxLength) {
        return p.size() == 1 && p.get(field) instanceof String s && !s.isBlank() && s.length() <= maxLength;
    }

    private static boolean credential(Map<String, Object> p) {
        return p.size() == 1 && p.get("passkey") instanceof Map<?, ?> m && !m.isEmpty();
    }

    private static String random(int bytes) {
        byte[] out = new byte[bytes];
        RANDOM.nextBytes(out);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
    }
}
