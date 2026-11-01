package faye.screens;

import faye.engine.CredentialIssuer;
import faye.engine.Env;
import faye.engine.ScreenRegistry;
import faye.engine.ScreenSpec;
import faye.engine.Submission;
import faye.ir.Entry;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The screens of {@code src/schema/screen.schema.json}, which the Login UI renders; {@code
 * BuiltinScreenTest} keeps the two lists equal. The {@code identifier}, {@code passkey}, {@code
 * otp_enroll}, {@code code_sent} and {@code phone_enroll} screens show credential material, which a
 * {@link CredentialIssuer} supplies: these specs hold only the non-credential part of each screen.
 * {@code code_sent} and {@code phone_enroll} show a code Faye delivers, and {@code qr} and {@code
 * qr_approve} the two sides of QR code sign in (ADR 0032); these take actions their issuer answers,
 * and are registered only with their issuer.
 */
public enum BuiltinScreen implements ScreenSpec {
    IDENTIFIER("identifier"),
    PASSWORD("password"),
    METHOD_PICKER(ScreenRegistry.METHOD_PICKER),
    PASSKEY("passkey"),
    OTP("otp"),
    OTP_ENROLL("otp_enroll"),
    CODE_SENT("code_sent"),
    PHONE_ENROLL("phone_enroll"),
    QR("qr"),
    QR_APPROVE("qr_approve"),
    CONSENT("consent"),
    ERROR("error");

    private final String wireName;

    BuiltinScreen(String wireName) {
        this.wireName = wireName;
    }

    /** A phone number as the person types it with its country code (E.164). */
    public static final String E164 = "\\+[1-9][0-9]{6,14}";

    /**
     * A registry of every built in screen, with the issuers of the credential module in use. The
     * screens of delivered codes ({@code code_sent}, {@code phone_enroll}) are registered only when
     * their issuer is, so without code delivery a flow naming them fails the checker (ADR 0032).
     */
    public static ScreenRegistry registry(List<? extends CredentialIssuer> issuers) {
        var issuing = issuers.stream().map(CredentialIssuer::screen).collect(java.util.stream.Collectors.toSet());
        List<BuiltinScreen> screens = java.util.Arrays.stream(values())
                .filter(s -> !s.delivered() || issuing.contains(s.wireName))
                .toList();
        return new ScreenRegistry(screens, issuers);
    }

    private boolean delivered() {
        return this == CODE_SENT || this == PHONE_ENROLL || this == QR || this == QR_APPROVE;
    }

    @Override
    public String type() {
        return wireName;
    }

    @Override
    public Set<String> options() {
        return switch (this) {
            case IDENTIFIER -> Set.of("passkey", "password");
            case PASSKEY -> Set.of("register", "required");
            case PHONE_ENROLL -> Set.of("required");
            case ERROR -> Set.of("code");
            case CODE_SENT -> Set.of("channel");
            case PASSWORD, METHOD_PICKER, OTP, OTP_ENROLL, CONSENT, QR, QR_APPROVE -> Set.of();
        };
    }

    /** The consent screen asks a signed in person; it belongs in consent entries only (ADR 0026). */
    @Override
    public Set<Entry.Kind> entries() {
        return switch (this) {
            case CONSENT -> Set.of(Entry.Kind.CONSENT);
            // A QR code is shown by the device that signs in, and approved only on the phone (ADR 0032)
            case QR -> Set.of(Entry.Kind.AUTHENTICATE);
            case QR_APPROVE -> Set.of(Entry.Kind.APPROVE);
            default -> EnumSet.allOf(Entry.Kind.class);
        };
    }

    /** A QR code can only be approved by the flow's approve entry. */
    @Override
    public java.util.Optional<Entry.Kind> needsEntry() {
        return this == QR ? java.util.Optional.of(Entry.Kind.APPROVE) : java.util.Optional.empty();
    }

    /**
     * Registering a passkey is for the signed in subject, as {@code webauthn.register} is; so is
     * adding a phone number.
     */
    @Override
    public boolean requiresSubject(Map<String, Object> opts) {
        return (this == PASSKEY && Boolean.TRUE.equals(opts.get("register")))
                || this == PHONE_ENROLL
                || this == QR_APPROVE;
    }

    /** A delivered code can be resent; a number being added is sent its first code by an action. */
    @Override
    public Set<String> actions() {
        return switch (this) {
            case CODE_SENT -> Set.of("resend");
            case PHONE_ENROLL -> Set.of("send");
            case QR -> Set.of("poll");
            default -> Set.of();
        };
    }

    @Override
    public boolean acceptsAction(String action, Map<String, Object> input, Map<String, Object> data) {
        return switch (this) {
            case CODE_SENT -> "resend".equals(action) && input.isEmpty();
            case QR -> "poll".equals(action) && input.isEmpty();
            case PHONE_ENROLL ->
                "send".equals(action) && input.size() == 1 && input.get("phone") instanceof String p && p.matches(E164);
            default -> false;
        };
    }

    /** The identifier screen only for passkey autofill; see {@link #prepare}. */
    @Override
    public boolean issued() {
        return switch (this) {
            case IDENTIFIER, PASSKEY, OTP_ENROLL, CODE_SENT, PHONE_ENROLL, QR, QR_APPROVE -> true;
            case PASSWORD, METHOD_PICKER, OTP, CONSENT, ERROR -> false;
        };
    }

    /** {@code /login} for who, {@code /consent} and {@code /error}; every other screen is a challenge. */
    @Override
    public String path(@Nullable String step) {
        return switch (this) {
            case IDENTIFIER -> "/login";
            case CONSENT -> "/consent";
            case ERROR -> "/error";
            case PASSWORD, METHOD_PICKER, PASSKEY, OTP, OTP_ENROLL, CODE_SENT, PHONE_ENROLL, QR, QR_APPROVE ->
                ScreenSpec.super.path(step);
        };
    }

    /**
     * Options: {@code passkey} (identifier: offer passkey autofill, for which the issuer issues a
     * challenge), {@code password} (identifier: ask for the password on the same screen), {@code
     * register} (passkey: create rather than use one), {@code required} (passkey with register,
     * phone_enroll: the person may not decline; ADR 0038), {@code code} (error), {@code channel}
     * (code_sent: {@code email} or {@code sms}, fixed by its block).
     */
    @Override
    public Map<String, Object> prepare(Env env, Map<String, Object> opts, Map<String, Object> previous) {
        Map<String, Object> data = new LinkedHashMap<>();
        switch (this) {
            case IDENTIFIER -> {
                if (Boolean.TRUE.equals(opts.get("password"))) data.put("password", true);
                // A tenant setting, not a flow option: it never changes what a flow can prove (ADR 0033)
                if (Boolean.TRUE.equals(env.tenant().get("remember_me"))) data.put("remember", true);
            }
            case PASSKEY -> {
                if (Boolean.TRUE.equals(opts.get("register"))) {
                    data.put("register", true);
                    if (Boolean.TRUE.equals(opts.get("required"))) data.put("required", true);
                }
            }
            case PHONE_ENROLL -> {
                if (Boolean.TRUE.equals(opts.get("required"))) data.put("required", true);
            }
            case ERROR -> data.put("code", opts.get("code") instanceof String c ? c : "error");
            case CODE_SENT -> data.put("channel", "sms".equals(opts.get("channel")) ? "sms" : "email");
            case PASSWORD, METHOD_PICKER, OTP, OTP_ENROLL, CONSENT, QR, QR_APPROVE -> {}
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
                if (Boolean.TRUE.equals(data.get("password"))) out.put("password", true);
                if (Boolean.TRUE.equals(data.get("remember"))) out.put("remember", true);
            }
            case METHOD_PICKER -> out.put("options", data.get("options"));
            case CONSENT -> {
                Map<String, Object> client = new LinkedHashMap<>();
                client.put("id", env.client().getOrDefault("client_id", ""));
                if (env.client().get("client_name") instanceof String n && !n.isEmpty()) client.put("name", n);
                out.put("client", client);
                out.put("scopes", env.host().get("requested_scope") instanceof List<?> l ? l : List.of());
            }
            case ERROR -> out.put("code", data.get("code"));
            case CODE_SENT -> out.put("channel", data.get("channel"));
            case QR_APPROVE -> {
                if (env.client().get("client_name") instanceof String n && !n.isEmpty()) {
                    out.put("client", Map.of("name", n));
                }
            }
            // An enrollment the person must complete offers no "Not now" (ADR 0038)
            case PASSKEY, PHONE_ENROLL -> {
                if (Boolean.TRUE.equals(data.get("required"))) out.put("required", true);
            }
            case OTP_ENROLL, PASSWORD, OTP, QR -> {}
        }
        return out;
    }

    @Override
    public boolean accepts(Submission input, Map<String, Object> data) {
        Map<String, Object> p = input.payload();
        return switch (this) {
            case IDENTIFIER ->
                Boolean.TRUE.equals(data.get("password"))
                        ? p.size() == 2 && field(p, "identifier", 256) && field(p, "password", 1024)
                        : text(p, "identifier", 256) || (data.containsKey("challenge") && credential(p));
            case PASSWORD -> text(p, "password", 1024);
            case METHOD_PICKER ->
                p.size() == 1 && data.get("options") instanceof List<?> l && l.contains(p.get("method"));
            case PASSKEY ->
                credential(p)
                        || (Boolean.TRUE.equals(data.get("register"))
                                && skippable(data)
                                && p.size() == 1
                                && Boolean.TRUE.equals(p.get("skip")));
            case OTP, OTP_ENROLL, CODE_SENT ->
                p.size() == 1 && p.get("code") instanceof String c && c.matches("[0-9]{6}");
            // Adding a phone may be declined, as adding a passkey may, unless the flow requires it
            case PHONE_ENROLL ->
                p.size() == 1
                        && ((p.get("code") instanceof String c && c.matches("[0-9]{6}"))
                                || (skippable(data) && Boolean.TRUE.equals(p.get("skip"))));
            case CONSENT -> p.size() == 1 && ("allow".equals(p.get("decision")) || "deny".equals(p.get("decision")));
            // The waiting device answers once a poll read the approval; it carries nothing
            case QR -> p.isEmpty();
            case QR_APPROVE ->
                p.size() == 1
                        && p.get("number") instanceof Number n
                        && n.longValue() >= 10
                        && n.longValue() <= 99
                        && n.doubleValue() == n.longValue();
            case ERROR -> p.isEmpty();
        };
    }

    /** An enrollment the person may decline: one its step does not require (ADR 0038). */
    private static boolean skippable(Map<String, Object> data) {
        return !Boolean.TRUE.equals(data.get("required"));
    }

    /** The identifier screen, when its tenant offers "keep me signed in". */
    @Override
    public boolean offersRemember(Map<String, Object> data) {
        return this == IDENTIFIER && Boolean.TRUE.equals(data.get("remember"));
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
        return p.size() == 1 && field(p, field, maxLength);
    }

    private static boolean field(Map<String, Object> p, String field, int maxLength) {
        return p.get(field) instanceof String s && !s.isBlank() && s.length() <= maxLength;
    }

    private static boolean credential(Map<String, Object> p) {
        return p.size() == 1 && p.get("passkey") instanceof Map<?, ?> m && !m.isEmpty();
    }
}
