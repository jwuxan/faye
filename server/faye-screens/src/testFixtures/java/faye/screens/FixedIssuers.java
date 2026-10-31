package faye.screens;

import faye.engine.CredentialIssuer;
import faye.engine.Env;
import faye.engine.ScreenRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Issuers for the built in screens that issue fixed material, for tests that run flows without a
 * credential module: the challenge is always {@link #CHALLENGE}, the secret {@link #SECRET} unless a
 * retry keeps the previous one.
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
        return List.of(issuer("identifier", true), issuer("passkey", false), issuer("otp_enroll", false));
    }

    private static CredentialIssuer issuer(String screen, boolean onlyForPasskeyOption) {
        return new CredentialIssuer() {
            @Override
            public String screen() {
                return screen;
            }

            @Override
            public Map<String, Object> issue(Env env, Map<String, Object> opts, Map<String, Object> previous) {
                if (screen.equals("otp_enroll")) {
                    return Map.of("secret", previous.get("secret") instanceof String s ? s : SECRET);
                }
                if (onlyForPasskeyOption && !Boolean.TRUE.equals(opts.get("passkey"))) return Map.of();
                return Map.of("challenge", CHALLENGE);
            }

            @Override
            public Map<String, Object> present(Env env, Map<String, Object> issued) {
                Map<String, Object> out = new LinkedHashMap<>();
                switch (screen) {
                    case "identifier" -> {
                        if (issued.containsKey("challenge")) out.put("passkey", Map.of("challenge", CHALLENGE));
                    }
                    case "passkey" -> out.put("challenge", issued.get("challenge"));
                    default -> out.put("secret", issued.get("secret"));
                }
                return out;
            }
        };
    }
}
