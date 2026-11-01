package faye.handlers;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import faye.engine.Env;
import faye.engine.Handler;
import faye.engine.HandlerRegistry;
import faye.engine.Invocation;
import faye.engine.Outcome;
import faye.engine.Submission;
import faye.engine.testing.InMemoryCredentials;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The built in handlers under arbitrary submissions (ADR 0007): any payload the browser posts
 * resolves to an {@link Outcome} or one of the documented rejections, never an unchecked throw of
 * anything else.
 */
class BuiltinHandlersFuzzTest {

    private static InMemoryCredentials creds() {
        var c = new InMemoryCredentials();
        c.addUser("user-alice", "alice@example.com", "right", "SECRET");
        c.addUser("user-bob", "bob@example.com", null, null);
        return c;
    }

    private static Invocation call(Map<String, Object> payload, Map<String, Object> context) {
        var env = new Env(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("identifier", "alice@example.com"),
                Map.of(),
                Env.Auth.NONE);
        return new Invocation(env, new Submission(payload, context), Map.of(), "f_fuzz", "default", false);
    }

    private static Map<String, Object> passkey(FuzzedDataProvider data) {
        Map<String, Object> response = new HashMap<>();
        if (data.consumeBoolean()) response.put("userHandle", data.consumeString(40));
        Map<String, Object> credential = new HashMap<>();
        if (data.consumeBoolean()) credential.put("credentialId", data.consumeString(40));
        if (data.consumeBoolean()) credential.put("userVerified", data.consumeBoolean());
        if (data.consumeBoolean()) credential.put("response", response);
        return credential;
    }

    @FuzzTest
    void submissions(FuzzedDataProvider data) {
        InMemoryCredentials creds = creds();
        List<Handler> all = new java.util.ArrayList<>(BuiltinHandlers.create(creds, creds, creds, creds));
        all.addAll(DeliveredCodes.handlers(creds, creds, new byte[32], java.time.Clock.systemUTC()));
        var grants = new faye.engine.testing.InMemoryQrGrants();
        if (data.consumeBoolean()) {
            grants.create(new faye.engine.spi.QrGrants.Grant(
                    QrSignIn.hash("t" + data.consumeInt(0, 2)),
                    data.pickValue(List.of("f", "g")),
                    "default",
                    data.consumeBoolean(),
                    Map.of(),
                    data.consumeInt(10, 99),
                    "",
                    data.consumeLong(),
                    data.pickValue(faye.engine.spi.QrGrants.Status.values()),
                    data.consumeBoolean() ? data.pickValue(List.of("alice", "bob")) : null,
                    data.consumeBoolean() ? List.of("pwd") : List.of()));
        }
        all.addAll(QrSignIn.handlers(grants, creds, java.time.Clock.systemUTC()));
        HandlerRegistry handlers = new HandlerRegistry(all);
        String name = data.pickValue(List.of(
                "qr.redeem",
                "qr.approve",
                "email_code.verify",
                "sms_code.verify",
                "phone.enroll",
                "identity.resolve",
                "password.verify",
                "totp.verify",
                "totp.enroll",
                "webauthn.register",
                "webauthn.assert",
                "consent.record"));
        Handler handler = handlers.find(name).orElseThrow();
        Map<String, Object> payload = new HashMap<>();
        if (data.consumeBoolean()) payload.put("identifier", data.consumeString(64));
        if (data.consumeBoolean()) payload.put("password", data.consumeString(64));
        if (data.consumeBoolean()) payload.put("code", data.consumeString(16));
        if (data.consumeBoolean()) payload.put("decision", data.pickValue(List.of("allow", "deny", "x")));
        if (data.consumeBoolean()) payload.put("skip", data.consumeBoolean());
        if (data.consumeBoolean()) payload.put("passkey", passkey(data));
        Map<String, Object> context = new HashMap<>();
        if (data.consumeBoolean()) context.put("secret", "S" + data.consumeInt(0, 9));
        if (data.consumeBoolean()) context.put("challenge", creds.challenge());
        if (data.consumeBoolean()) context.put("channel", data.pickValue(List.of("email", "sms", "x")));
        if (data.consumeBoolean()) context.put("mac", data.consumeString(48));
        if (data.consumeBoolean()) context.put("expires", data.consumeLong());
        if (data.consumeBoolean()) context.put("phone", data.consumeString(20));
        if (data.consumeBoolean()) context.put("token", "t" + data.consumeInt(0, 2));
        if (data.consumeBoolean()) context.put("grant", QrSignIn.hash("t" + data.consumeInt(0, 2)));
        if (data.consumeBoolean()) payload.put("number", data.consumeInt(0, 120));
        try {
            handler.invoke(call(payload, context));
        } catch (IllegalArgumentException | IllegalStateException | faye.engine.FlowException e) {
            // Documented rejections: a screen answered out of place or malformed material
        }
    }

    @Test
    void anEmptySubmissionResolves() {
        InMemoryCredentials creds = creds();
        var resolve = new HandlerRegistry(BuiltinHandlers.create(creds, creds, creds, creds))
                .find("identity.resolve")
                .orElseThrow();
        var out = (Outcome.Ok) resolve.invoke(call(Map.of(), Map.of()));
        assertThat(out.user()).containsEntry("exists", false);
    }
}
