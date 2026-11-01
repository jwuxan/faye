package faye.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import faye.engine.CredentialIssuer;
import faye.engine.CredentialIssuer.Acted;
import faye.engine.CredentialIssuer.Context;
import faye.engine.Effect;
import faye.engine.Env;
import faye.engine.Handler;
import faye.engine.Invocation;
import faye.engine.Outcome;
import faye.engine.Submission;
import faye.engine.spi.CodeOutbox.Channel;
import faye.engine.testing.InMemoryCredentials;
import faye.engine.testing.InMemoryOutbox;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The issuer and handlers of delivered codes (ADR 0032): the code is never presented and only its
 * MAC is kept, the destination is the account's, unknown accounts look the same and never verify,
 * and the per screen limits hold.
 */
class DeliveredCodesTest {

    static final byte[] KEY = new byte[32];
    static final Context FLOW = new Context("f_one", "acme", false);
    static final Map<String, Object> EMAIL = Map.of("channel", "email");
    static final Map<String, Object> SMS = Map.of("channel", "sms");

    final InMemoryCredentials creds = new InMemoryCredentials();
    final InMemoryOutbox outbox = new InMemoryOutbox();
    final MutableClock clock = new MutableClock();
    CredentialIssuer codeSent;
    CredentialIssuer phoneEnroll;
    Map<String, Handler> handlers = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        creds.addUser("alice", "alice@example.com", "pw", null);
        creds.enroll("alice", "+15551234821");
        creds.addUser("bob", "bob@example.com", "pw", null);
        var issuers = DeliveredCodes.issuers(creds, outbox, KEY, clock);
        codeSent = issuers.get(0);
        phoneEnroll = issuers.get(1);
        DeliveredCodes.handlers(creds, creds, KEY, clock).forEach(h -> handlers.put(h.name(), h));
    }

    Handler handler(String name) {
        return java.util.Objects.requireNonNull(handlers.get(name), name);
    }

    static Env typed(String identifier) {
        return new Env(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of("identifier", identifier), Map.of(), Env.Auth.NONE);
    }

    static Env signedIn(String subject) {
        return new Env(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("identifier", subject + "@example.com"),
                Map.of(),
                new Env.Auth(subject, List.of()));
    }

    /** The data the screen waits with: the spec's channel plus what the issuer issued. */
    Map<String, Object> issue(Env env, Map<String, Object> opts) {
        Map<String, Object> data = new LinkedHashMap<>(opts);
        data.putAll(codeSent.issue(env, FLOW, opts, Map.of()));
        return data;
    }

    Outcome answer(String handler, Env env, Map<String, Object> data, String code) {
        return handler(handler)
                .invoke(new Invocation(
                        env, new Submission(Map.of("code", code), data), Map.of(), FLOW.flowId(), "acme", false));
    }

    @Test
    void anEmailedCodeGoesToTheVerifiedAddressAndOnlyItsMacIsKept() {
        Map<String, Object> data = issue(typed("alice@example.com"), EMAIL);
        String code = outbox.lastCode();
        assertThat(outbox.sent()).singleElement().satisfies(s -> {
            assertThat(s.channel()).isEqualTo(Channel.EMAIL);
            assertThat(s.destination()).isEqualTo("alice@example.com");
            assertThat(s.tenant()).isEqualTo("acme");
        });
        assertThat(code).matches("[0-9]{6}");
        assertThat(data.values()).doesNotContain(code);
        assertThat(data.toString()).doesNotContain(code).doesNotContain("alice@example.com");
        Map<String, Object> shown = codeSent.present(typed("alice@example.com"), data);
        assertThat(shown).containsEntry("to", "a•••@example.com").containsEntry("expiresIn", 300L);
        assertThat(shown.toString()).doesNotContain(code);

        assertThat(answer("email_code.verify", typed("alice@example.com"), data, code))
                .isEqualTo(new Outcome.Ok(Map.of(), "alice", null));
        assertThat(answer("email_code.verify", typed("alice@example.com"), data, wrong(code)))
                .isEqualTo(new Outcome.Fail("invalid_code"));
    }

    @Test
    void aCodeIsBoundToItsFlow() {
        Map<String, Object> data = issue(typed("alice@example.com"), EMAIL);
        String code = outbox.lastCode();
        Outcome elsewhere = handler("email_code.verify")
                .invoke(new Invocation(
                        typed("alice@example.com"),
                        new Submission(Map.of("code", code), data),
                        Map.of(),
                        "f_other",
                        "acme",
                        false));
        assertThat(elsewhere).isEqualTo(new Outcome.Fail("invalid_code"));
    }

    @Test
    void anUnknownAccountLooksTheSameAndNeverVerifies() {
        Map<String, Object> data = issue(typed("nobody@example.com"), EMAIL);
        assertThat(outbox.sent()).singleElement().satisfies(s -> {
            assertThat(s.destination()).isNull();
            assertThat(s.limitKey()).isEqualTo("nobody@example.com");
        });
        Map<String, Object> real = issue(typed("alice@example.com"), EMAIL);
        assertThat(data.keySet()).isEqualTo(real.keySet());
        assertThat(codeSent.present(typed("nobody@example.com"), data))
                .containsEntry("to", "n•••@example.com")
                .containsOnlyKeys(
                        codeSent.present(typed("alice@example.com"), real).keySet());
        // Not even the code the outbox was handed
        assertThat(answer(
                        "email_code.verify",
                        typed("nobody@example.com"),
                        data,
                        outbox.sent().getFirst().code()))
                .isEqualTo(new Outcome.Fail("invalid_code"));
    }

    @Test
    void aTextedCodeNeedsTheSwitchAndNeverTakesAnotherNumber() {
        Map<String, Object> data = issue(typed("alice@example.com"), SMS);
        assertThat(outbox.sent().getLast().destination()).isEqualTo("+15551234821");
        assertThat(codeSent.present(typed("alice@example.com"), data)).containsEntry("to", "••• ••• 4821");
        assertThat(answer("sms_code.verify", typed("alice@example.com"), data, outbox.lastCode()))
                .isEqualTo(new Outcome.Ok(Map.of(), "alice", null));
        // Bob has no phone codes: nothing is delivered and nothing verifies
        Map<String, Object> bob = issue(typed("bob@example.com"), SMS);
        assertThat(outbox.sent().getLast().destination()).isNull();
        assertThat(answer("sms_code.verify", typed("bob@example.com"), bob, outbox.lastCode()))
                .isEqualTo(new Outcome.Fail("invalid_code"));
    }

    @Test
    void aHandlerAnswersOnlyItsChannel() {
        Map<String, Object> data = issue(typed("alice@example.com"), EMAIL);
        assertThatThrownBy(() -> answer("sms_code.verify", typed("alice@example.com"), data, outbox.lastCode()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anExpiredCodeIsRefusedAndAResendSendsAnother() {
        Map<String, Object> data = issue(typed("alice@example.com"), EMAIL);
        String first = outbox.lastCode();
        clock.advance(DeliveredCodes.LIFETIME_MILLIS);
        assertThat(answer("email_code.verify", typed("alice@example.com"), data, first))
                .isEqualTo(new Outcome.Fail("code_expired"));
        Acted acted = codeSent.act(typed("alice@example.com"), FLOW, EMAIL, data, "resend", Map.of());
        assertThat(acted.error()).isNull();
        data.putAll(acted.data());
        assertThat(outbox.sent()).hasSize(2);
        if (!first.equals(outbox.lastCode())) {
            assertThat(answer("email_code.verify", typed("alice@example.com"), data, first))
                    .as("the first code is replaced")
                    .isEqualTo(new Outcome.Fail("invalid_code"));
        }
        assertThat(answer("email_code.verify", typed("alice@example.com"), data, outbox.lastCode()))
                .isEqualTo(new Outcome.Ok(Map.of(), "alice", null));
    }

    @Test
    void resendsWaitForTheCooldownAndStopAtThree() {
        Map<String, Object> data = issue(typed("alice@example.com"), EMAIL);
        assertThat(codeSent.act(typed("alice@example.com"), FLOW, EMAIL, data, "resend", Map.of()))
                .isEqualTo(Acted.refused(DeliveredCodes.SEND_LIMITED));
        for (int i = 2; i <= DeliveredCodes.MAX_SENDS; i++) {
            clock.advance(DeliveredCodes.COOLDOWN_MILLIS);
            Acted acted = codeSent.act(typed("alice@example.com"), FLOW, EMAIL, data, "resend", Map.of());
            assertThat(acted.error()).isNull();
            data.putAll(acted.data());
        }
        assertThat(codeSent.present(typed("alice@example.com"), data)).doesNotContainKey("resendIn");
        clock.advance(DeliveredCodes.COOLDOWN_MILLIS);
        assertThat(codeSent.act(typed("alice@example.com"), FLOW, EMAIL, data, "resend", Map.of()))
                .isEqualTo(Acted.refused(DeliveredCodes.SEND_LIMITED));
        assertThat(outbox.sent()).hasSize(DeliveredCodes.MAX_SENDS);
    }

    @Test
    void aSendTheOutboxRefusesIsSendLimited() {
        outbox.limit(1);
        Map<String, Object> data = issue(typed("alice@example.com"), EMAIL);
        clock.advance(DeliveredCodes.COOLDOWN_MILLIS);
        assertThat(codeSent.act(typed("alice@example.com"), FLOW, EMAIL, data, "resend", Map.of()))
                .isEqualTo(Acted.refused(DeliveredCodes.SEND_LIMITED));
    }

    @Test
    void aRetryKeepsTheCodeThatWasSent() {
        Map<String, Object> data = issue(typed("alice@example.com"), EMAIL);
        Map<String, Object> again = codeSent.issue(typed("alice@example.com"), FLOW, EMAIL, data);
        assertThat(outbox.sent()).hasSize(1);
        assertThat(again).containsEntry("mac", data.get("mac"));
        assertThat(codeSent.resumable(typed("alice@example.com"), data)).isTrue();
        assertThat(codeSent.resumable(typed("alice@example.com"), Map.of("channel", "email")))
                .isFalse();
    }

    @Test
    void addingAPhoneProvesTheNumberAndWritesItOnCommit() {
        Env alice = signedIn("bob");
        Map<String, Object> data = new LinkedHashMap<>(phoneEnroll.issue(alice, FLOW, Map.of(), Map.of()));
        assertThat(phoneEnroll.present(alice, data)).isEmpty();
        assertThat(outbox.sent()).isEmpty();
        Acted sent = phoneEnroll.act(alice, FLOW, Map.of(), data, "send", Map.of("phone", "+15559990000"));
        assertThat(sent.error()).isNull();
        data.putAll(sent.data());
        assertThat(outbox.sent().getLast().destination()).isEqualTo("+15559990000");
        assertThat(phoneEnroll.present(alice, data)).containsEntry("to", "••• ••• 0000");
        String code = outbox.lastCode();
        Handler enroll = handler("phone.enroll");
        Outcome wrong = enroll.invoke(new Invocation(
                alice, new Submission(Map.of("code", wrong(code)), data), Map.of(), FLOW.flowId(), "acme", false));
        assertThat(wrong).isEqualTo(new Outcome.Fail("invalid_code"));
        Outcome ok = enroll.invoke(new Invocation(
                alice, new Submission(Map.of("code", code), data), Map.of(), FLOW.flowId(), "acme", false));
        assertThat(ok).isInstanceOfSatisfying(Outcome.Ok.class, o -> {
            assertThat(o.user()).isEqualTo(Map.of("has_phone", true));
            assertThat(o.effects())
                    .singleElement()
                    .extracting(Effect::data)
                    .isEqualTo(Map.of("subject", "bob", "phone", "+15559990000"));
        });
        assertThat(creds.bySubject("bob").orElseThrow().hasPhone()).isFalse();
        enroll.commit(((Outcome.Ok) ok).effects().getFirst());
        assertThat(creds.bySubject("bob").orElseThrow().phone()).isEqualTo("+15559990000");
    }

    @Test
    void addingAPhoneMayBeDeclined() {
        Outcome skipped = handler("phone.enroll")
                .invoke(new Invocation(
                        signedIn("bob"), new Submission(Map.of("skip", true), Map.of()), Map.of(), "f", "acme", false));
        assertThat(skipped).isEqualTo(new Outcome.Ok(Map.of(), null, null));
    }

    @Test
    void addingAPhoneNeedsASignedInSubject() {
        assertThatThrownBy(() -> phoneEnroll.issue(typed("bob@example.com"), FLOW, Map.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class);
        Outcome nobody = handler("phone.enroll")
                .invoke(new Invocation(
                        typed("bob@example.com"),
                        new Submission(Map.of("code", "123456"), Map.of()),
                        Map.of(),
                        "f",
                        "acme",
                        false));
        assertThat(nobody).isEqualTo(new Outcome.Fail("unauthenticated"));
    }

    @Test
    void anAccountOfAnotherTenantIsUnknown() {
        creds.addUser("carol", "carol@example.com", null, true, "pw", null, List.of(), false, "bank", false);
        Map<String, Object> data = issue(typed("carol@example.com"), EMAIL);
        assertThat(outbox.sent().getLast().destination()).isNull();
        assertThat(answer("email_code.verify", typed("carol@example.com"), data, outbox.lastCode()))
                .isEqualTo(new Outcome.Fail("invalid_code"));
    }

    @Test
    void masks() {
        assertThat(DeliveredCodes.maskEmail("alice@example.com")).isEqualTo("a•••@example.com");
        assertThat(DeliveredCodes.maskEmail("alice")).isEqualTo("a•••");
        assertThat(DeliveredCodes.maskEmail("")).isEqualTo("•••");
        assertThat(DeliveredCodes.maskPhone("+15551234821")).isEqualTo("••• ••• 4821");
        assertThat(DeliveredCodes.maskPhone("")).isEqualTo("••• ••• ••••");
    }

    @Test
    void aShortKeyIsRefused() {
        assertThatThrownBy(() -> DeliveredCodes.issuers(creds, outbox, new byte[16], clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String wrong(String code) {
        return "000000".equals(code) ? "000001" : "000000";
    }

    static final class MutableClock extends Clock {
        long millis = 1_000_000L;

        void advance(long by) {
            millis += by;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
