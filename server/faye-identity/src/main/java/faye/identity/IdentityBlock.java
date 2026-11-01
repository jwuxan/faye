package faye.identity;

import faye.dsl.BlockSpec;
import faye.dsl.BlockSpec.Setting.Target;
import java.util.Map;

/**
 * The blocks of the identity handlers (ADR 0030): each one step with its screen and handler fixed,
 * which a flow names by id ({@code block: password}) and tunes only through the settings declared
 * here. Attempts default to three wherever a typo is likely; an identifier asks for the password
 * on its own screen only when the flow says so.
 */
public enum IdentityBlock implements BlockSpec {
    IDENTIFIER("identifier", "Identifier", "identity.resolve", "identifier", Role.IDENTIFIER),
    PASSWORD("password", "Password", "password.verify", "password", Role.PASSWORD),
    PASSKEY("passkey", "Passkey", "webauthn.assert", "passkey", Role.CREDENTIAL),
    ADD_PASSKEY("add-passkey", "Add passkey", "webauthn.register", "passkey", Role.ACTION),
    TOTP("totp", "TOTP", "totp.verify", "otp", Role.CREDENTIAL),
    ENROLL_TOTP("enroll-totp", "Enroll TOTP", "totp.enroll", "otp_enroll", Role.CREDENTIAL),
    CONSENT("consent", "Consent", "consent.record", "consent", Role.CONSENT);

    private static final Setting THREE_ATTEMPTS = new Setting(3, Target.ATTEMPTS);

    private final String id;
    private final String title;
    private final String handler;
    private final String screen;
    private final Role role;

    IdentityBlock(String id, String title, String handler, String screen, Role role) {
        this.id = id;
        this.title = title;
        this.handler = handler;
        this.screen = screen;
        this.role = role;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String handler() {
        return handler;
    }

    @Override
    public String screen() {
        return screen;
    }

    @Override
    public Role role() {
        return role;
    }

    /** Adding a passkey is the passkey screen in its registering form. */
    @Override
    public Map<String, Object> fixed() {
        return this == ADD_PASSKEY ? Map.of("register", true) : Map.of();
    }

    /**
     * The identifier: {@code passkey} offers passkey autofill; {@code password} asks for the
     * password on the same screen, with {@code attempts}. A passkey is one attempt, a typed secret
     * three; adding a passkey and consent are a single answer.
     */
    @Override
    public Map<String, Setting> settings() {
        return switch (this) {
            case IDENTIFIER ->
                Map.of(
                        "passkey", new Setting(false, Target.OPTION),
                        "password", new Setting(false, Target.PASSWORD),
                        "attempts", THREE_ATTEMPTS);
            case PASSWORD, TOTP, ENROLL_TOTP -> Map.of("attempts", THREE_ATTEMPTS);
            case PASSKEY -> Map.of("attempts", new Setting(1, Target.ATTEMPTS));
            case ADD_PASSKEY, CONSENT -> Map.of();
        };
    }
}
