package faye.handlers;

import faye.dsl.BlockSpec;
import faye.dsl.BlockSpec.Setting.Target;
import java.util.Map;

/**
 * The blocks of the built in handlers (ADR 0030): each one step with its screen and handler fixed,
 * which a flow names by id ({@code block: password}) and tunes only through the settings declared
 * here. Attempts default to three wherever a typo is likely; an identifier asks for the password
 * on its own screen only when the flow says so.
 */
public enum BuiltinBlock implements BlockSpec {
    IDENTIFIER("identifier", "Identifier", "identity.resolve", "identifier", Role.IDENTIFIER),
    PASSWORD("password", "Password", "password.verify", "password", Role.PASSWORD),
    PASSKEY("passkey", "Passkey", "webauthn.assert", "passkey", Role.CREDENTIAL),
    ADD_PASSKEY("add-passkey", "Add passkey", "webauthn.register", "passkey", Role.ACTION),
    TOTP("totp", "TOTP", "totp.verify", "otp", Role.CREDENTIAL),
    ENROLL_TOTP("enroll-totp", "Enroll TOTP", "totp.enroll", "otp_enroll", Role.CREDENTIAL),
    EMAIL_CODE("email-code", "Email code", "email_code.verify", "code_sent", Role.CREDENTIAL),
    SMS_CODE("sms-code", "Text message code", "sms_code.verify", "code_sent", Role.CREDENTIAL),
    ADD_PHONE("add-phone", "Add phone", "phone.enroll", "phone_enroll", Role.ACTION),
    QR("qr", "QR code", "qr.redeem", "qr", Role.CREDENTIAL),
    QR_APPROVE("qr-approve", "Approve QR code", "qr.approve", "qr_approve", Role.APPROVE),
    CONSENT("consent", "Consent", "consent.record", "consent", Role.CONSENT);

    private static final Setting THREE_ATTEMPTS = new Setting(3, Target.ATTEMPTS);

    /** An enrollment the person must complete: the screen offers no "Not now" (ADR 0038). */
    private static final Setting REQUIRED = new Setting(false, Target.OPTION);

    private final String id;
    private final String title;
    private final String handler;
    private final String screen;
    private final Role role;

    BuiltinBlock(String id, String title, String handler, String screen, Role role) {
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

    /**
     * Adding a passkey is the passkey screen in its registering form; a delivered code's channel is
     * its block's (ADR 0032).
     */
    @Override
    public Map<String, Object> fixed() {
        return switch (this) {
            case ADD_PASSKEY -> Map.of("register", true);
            case EMAIL_CODE -> Map.of("channel", "email");
            case SMS_CODE -> Map.of("channel", "sms");
            default -> Map.of();
        };
    }

    /**
     * The identifier: {@code passkey} offers passkey autofill; {@code password} asks for the
     * password on the same screen, with {@code attempts}. A passkey is one attempt, a typed secret
     * three; adding a passkey and consent are a single answer. Adding a passkey or a phone may be
     * declined unless {@code required} (ADR 0038).
     */
    @Override
    public Map<String, Setting> settings() {
        return switch (this) {
            case IDENTIFIER ->
                Map.of(
                        "passkey", new Setting(false, Target.OPTION),
                        "password", new Setting(false, Target.PASSWORD),
                        "attempts", THREE_ATTEMPTS);
            // A QR code that expired is shown afresh while attempts last
            case PASSWORD, TOTP, ENROLL_TOTP, EMAIL_CODE, SMS_CODE, QR -> Map.of("attempts", THREE_ATTEMPTS);
            case ADD_PHONE -> Map.of("attempts", THREE_ATTEMPTS, "required", REQUIRED);
            // Picking the wrong number refuses the grant: there is nothing to try again
            case QR_APPROVE -> Map.of();
            case PASSKEY -> Map.of("attempts", new Setting(1, Target.ATTEMPTS));
            case ADD_PASSKEY -> Map.of("required", REQUIRED);
            case CONSENT -> Map.of();
        };
    }
}
