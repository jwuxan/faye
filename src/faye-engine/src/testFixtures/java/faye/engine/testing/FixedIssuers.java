package faye.engine.testing;

import faye.engine.CredentialIssuer;
import faye.engine.Env;
import faye.engine.ScreenRegistry;
import faye.screens.BuiltinScreen;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Issuers for the built in screens that issue fixed material, for tests that run flows without a
 * credential module. They behave like the identity module's issuers (a passkey registration needs a
 * signed in subject) and present what the screen protocol requires; the challenge is always {@link
 * #CHALLENGE}, the secret {@link #SECRET} unless a retry keeps the previous one.
 */
public final class FixedIssuers {

    public static final String CHALLENGE = "test-challenge";
    public static final String SECRET = "JBSWY3DPEHPK3PXP";

    private FixedIssuers() {}

    /** Every built in screen, with these issuers. */
    public static ScreenRegistry registry() {
        return BuiltinScreen.registry(all());
    }

    public static List<CredentialIssuer> all() {
        return List.of(
                issuer("identifier", true),
                issuer("passkey", false),
                issuer("otp_enroll", false),
                delivered("code_sent"),
                delivered("phone_enroll"),
                qr(),
                qrApprove());
    }

    /** The QR code Faye's tests show: token {@code test-token}, number 42, approved once polled. */
    public static final long QR_NUMBER = 42;

    private static CredentialIssuer qr() {
        return new CredentialIssuer() {
            @Override
            public String screen() {
                return "qr";
            }

            @Override
            public Map<String, Object> issue(Env env, Map<String, Object> opts, Map<String, Object> previous) {
                return Map.of("token", "test-token", "status", "pending");
            }

            @Override
            public Acted act(
                    Env env,
                    Context context,
                    Map<String, Object> opts,
                    Map<String, Object> issued,
                    String action,
                    Map<String, Object> input) {
                return Acted.done(Map.of("status", "approved"));
            }

            @Override
            public boolean resumable(Env env, Map<String, Object> issued) {
                return issued.get("token") instanceof String;
            }

            @Override
            public Map<String, Object> present(Env env, Map<String, Object> issued) {
                return Map.of(
                        "url",
                        "https://login.test/approve/" + issued.get("token"),
                        "number",
                        QR_NUMBER,
                        "expiresIn",
                        120L,
                        "status",
                        issued.get("status"));
            }
        };
    }

    private static CredentialIssuer qrApprove() {
        return new CredentialIssuer() {
            @Override
            public String screen() {
                return "qr_approve";
            }

            @Override
            public Map<String, Object> issue(Env env, Map<String, Object> opts, Map<String, Object> previous) {
                if (env.auth().subject() == null) {
                    throw new IllegalStateException("approving needs a signed in subject");
                }
                return Map.of("choices", List.of(17L, QR_NUMBER, 73L));
            }

            @Override
            public boolean resumable(Env env, Map<String, Object> issued) {
                return env.auth().subject() != null && issued.get("choices") instanceof List<?>;
            }

            @Override
            public Map<String, Object> present(Env env, Map<String, Object> issued) {
                return Map.of("numbers", issued.get("choices"), "device", "Chrome on macOS");
            }
        };
    }

    /**
     * A delivered code's screen (ADR 0032): {@code code_sent} counts its sends from one, and {@code
     * phone_enroll} from none until the {@code send} action; it presents where the code went, and a
     * resend until the third send. The code itself is never issued: the handlers that verify it are
     * fakes.
     */
    private static CredentialIssuer delivered(String screen) {
        boolean enroll = "phone_enroll".equals(screen);
        return new CredentialIssuer() {
            @Override
            public String screen() {
                return screen;
            }

            @Override
            public Map<String, Object> issue(Env env, Map<String, Object> opts, Map<String, Object> previous) {
                if (enroll && env.auth().subject() == null) {
                    throw new IllegalStateException("adding a phone needs a signed in subject");
                }
                if (previous.get("sends") instanceof Number n) {
                    Map<String, Object> kept = new LinkedHashMap<>();
                    kept.put("sends", n.longValue());
                    if (previous.get("to") instanceof String to) kept.put("to", to);
                    return kept;
                }
                return enroll ? Map.of("sends", 0L) : Map.of("sends", 1L, "to", "a•••@example.com");
            }

            @Override
            public Acted act(
                    Env env,
                    Context context,
                    Map<String, Object> opts,
                    Map<String, Object> issued,
                    String action,
                    Map<String, Object> input) {
                long sends = issued.get("sends") instanceof Number n ? n.longValue() : 0L;
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("sends", sends + 1);
                if (input.get("phone") instanceof String phone) {
                    out.put("to", "••• ••• " + phone.substring(phone.length() - 4));
                }
                return Acted.done(out);
            }

            @Override
            public boolean resumable(Env env, Map<String, Object> issued) {
                return issued.get("sends") instanceof Number
                        && (!enroll || env.auth().subject() != null);
            }

            // A resend is offered until the third send; the timing is fixed
            @Override
            public Map<String, Object> present(Env env, Map<String, Object> issued) {
                Map<String, Object> out = new LinkedHashMap<>();
                if (issued.get("to") instanceof String to) {
                    out.put("to", to);
                    long sends = issued.get("sends") instanceof Number n ? n.longValue() : 0L;
                    if (sends < 3) out.put("resendIn", 30L);
                    out.put("expiresIn", 300L);
                }
                return out;
            }
        };
    }

    private static CredentialIssuer issuer(String screen, boolean onlyForPasskeyOption) {
        return new CredentialIssuer() {
            @Override
            public String screen() {
                return screen;
            }

            @Override
            public Map<String, Object> issue(Env env, Map<String, Object> opts, Map<String, Object> previous) {
                if ("otp_enroll".equals(screen)) {
                    return Map.of("secret", previous.get("secret") instanceof String s ? s : SECRET);
                }
                if (onlyForPasskeyOption && !Boolean.TRUE.equals(opts.get("passkey"))) return Map.of();
                if (Boolean.TRUE.equals(opts.get("register")) && env.auth().subject() == null) {
                    throw new IllegalStateException("passkey registration needs a signed in subject");
                }
                return Map.of("challenge", CHALLENGE);
            }

            @Override
            public boolean resumable(Env env, Map<String, Object> issued) {
                return switch (screen) {
                    case "passkey" ->
                        issued.get("challenge") instanceof String
                                && (!Boolean.TRUE.equals(issued.get("register"))
                                        || env.auth().subject() != null);
                    case "otp_enroll" -> issued.get("secret") instanceof String;
                    default -> true;
                };
            }

            @Override
            public Map<String, Object> present(Env env, Map<String, Object> issued) {
                Map<String, Object> out = new LinkedHashMap<>();
                switch (screen) {
                    case "identifier" -> {
                        if (issued.containsKey("challenge")) out.put("passkey", Map.of("challenge", CHALLENGE));
                    }
                    case "passkey" -> {
                        out.put("challenge", issued.get("challenge"));
                        if (Boolean.TRUE.equals(issued.get("register"))) {
                            String name = env.user().get("identifier") instanceof String id ? id : "account";
                            out.put(
                                    "register",
                                    Map.of(
                                            "userId",
                                            "dGVzdA",
                                            "userName",
                                            name,
                                            "displayName",
                                            name,
                                            "algorithms",
                                            List.of(-7L)));
                        }
                    }
                    default -> {
                        out.put("secret", issued.get("secret"));
                        out.put("uri", "otpauth://totp/Test:account?secret=" + issued.get("secret") + "&issuer=Test");
                    }
                }
                return out;
            }
        };
    }
}
