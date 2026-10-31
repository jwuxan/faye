package faye.screens;

import faye.engine.CredentialIssuer;
import faye.engine.Env;
import faye.engine.ScreenRegistry;
import faye.engine.ScreenSpec;
import faye.engine.Submission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The screens of {@code schema/screen.schema.json}, which the Login UI renders; {@code
 * BuiltinScreenTest} keeps the two lists equal. The {@code identifier}, {@code passkey} and {@code
 * otp_enroll} screens show credential material, which a {@link CredentialIssuer} supplies: these
 * specs hold only the non-credential part of each screen.
 */
public enum BuiltinScreen implements ScreenSpec {
    IDENTIFIER("identifier"),
    PASSWORD("password"),
    METHOD_PICKER(ScreenRegistry.METHOD_PICKER),
    PASSKEY("passkey"),
    OTP("otp"),
    OTP_ENROLL("otp_enroll"),
    CONSENT("consent"),
    ERROR("error");

    private final String wireName;

    BuiltinScreen(String wireName) {
        this.wireName = wireName;
    }

    /** A registry of every built in screen, with the issuers of the credential module in use. */
    public static ScreenRegistry registry(List<? extends CredentialIssuer> issuers) {
        return new ScreenRegistry(List.of(values()), issuers);
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

    /** The identifier screen only for passkey autofill; see {@link #prepare}. */
    @Override
    public boolean issued() {
        return switch (this) {
            case IDENTIFIER, PASSKEY, OTP_ENROLL -> true;
            case PASSWORD, METHOD_PICKER, OTP, CONSENT, ERROR -> false;
        };
    }

    /**
     * Options: {@code passkey} (identifier: offer passkey autofill, for which the issuer issues a
     * challenge), {@code register} (passkey: create rather than use one), {@code code} (error).
     */
    @Override
    public Map<String, Object> prepare(Env env, Map<String, Object> opts, Map<String, Object> previous) {
        Map<String, Object> data = new LinkedHashMap<>();
        switch (this) {
            case PASSKEY -> {
                if (Boolean.TRUE.equals(opts.get("register"))) data.put("register", true);
            }
            case ERROR -> data.put("code", opts.get("code") instanceof String c ? c : "error");
            case IDENTIFIER, PASSWORD, METHOD_PICKER, OTP, OTP_ENROLL, CONSENT -> {}
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
            case METHOD_PICKER -> out.put("options", data.get("options"));
            case CONSENT -> {
                Map<String, Object> client = new LinkedHashMap<>();
                client.put("id", env.client().getOrDefault("client_id", ""));
                if (env.client().get("client_name") instanceof String n && !n.isEmpty()) client.put("name", n);
                out.put("client", client);
                out.put("scopes", env.host().get("requested_scope") instanceof List<?> l ? l : List.of());
            }
            case ERROR -> out.put("code", data.get("code"));
            case IDENTIFIER, PASSKEY, OTP_ENROLL, PASSWORD, OTP -> {}
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
}
