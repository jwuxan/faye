package faye.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import faye.engine.CredentialIssuer;
import faye.engine.CredentialIssuer.Context;
import faye.engine.Env;
import faye.engine.Handler;
import faye.engine.Invocation;
import faye.engine.Outcome;
import faye.engine.Submission;
import faye.engine.testing.InMemoryCredentials;
import faye.engine.testing.InMemoryQrGrants;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * QR code sign in end to end over the real issuers and handlers (ADR 0032): the waiting device's
 * grant, the phone's matching number and approval, the poll, and the one redeem, for its own flow
 * only, proving no more than the phone proved.
 */
class QrSignInTest {

    static final String URL = "https://login.example/approve/";
    static final Context WAITING = new Context("f_waiting", "acme", false);

    final InMemoryCredentials creds = new InMemoryCredentials();
    final InMemoryQrGrants grants = new InMemoryQrGrants();
    final DeliveredCodesTest.MutableClock clock = new DeliveredCodesTest.MutableClock();
    CredentialIssuer qr;
    CredentialIssuer qrApprove;
    Handler redeem;
    Handler approve;

    @BeforeEach
    void setUp() {
        creds.addUser("alice", "alice@example.com", "pw", null);
        creds.addUser("bob", "bob@example.com", "pw", null);
        List<CredentialIssuer> issuers = QrSignIn.issuers(grants, URL, clock);
        qr = issuers.get(0);
        qrApprove = issuers.get(1);
        List<Handler> handlers = QrSignIn.handlers(grants, creds, clock);
        redeem = handlers.get(0);
        approve = handlers.get(1);
    }

    static Env waiting() {
        return new Env(
                Map.of("user_agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) Chrome/130.0 Safari/537.36"),
                Map.of("client_id", "web", "client_name", "Acme Web"),
                Map.of(),
                Map.of(),
                Map.of("identifier", "alice@example.com"),
                Map.of(),
                Env.Auth.NONE);
    }

    static Env phone(String subject, List<String> amr) {
        return new Env(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("identifier", subject + "@example.com"),
                Map.of(),
                new Env.Auth(subject, List.of(new Env.Verified("password.verify", amr))));
    }

    record Shown(Map<String, Object> data, String token, long number) {}

    Shown show() {
        Map<String, Object> data = new LinkedHashMap<>(qr.issue(waiting(), WAITING, Map.of(), Map.of()));
        Map<String, Object> shown = qr.present(waiting(), data);
        String url = (String) Objects.requireNonNull(shown.get("url"));
        assertThat(url).startsWith(URL);
        return new Shown(
                data, url.substring(URL.length()), ((Number) Objects.requireNonNull(shown.get("number"))).longValue());
    }

    /** The phone's approve flow, signed in as {@code subject}, picking {@code number}: the handler's outcome. */
    Outcome approveOn(String token, String subject, long number) {
        Context phoneFlow = new Context("f_phone", "acme", false, QrSignIn.hash(token));
        Map<String, Object> data = qrApprove.issue(phone(subject, List.of("pwd")), phoneFlow, Map.of(), Map.of());
        Map<String, Object> shown = qrApprove.present(phone(subject, List.of("pwd")), data);
        assertThat(shown).containsEntry("device", "Chrome on macOS");
        assertThat(String.valueOf(shown.get("numbers"))).contains(String.valueOf(number));
        assertThat((List<?>) shown.get("numbers")).hasSize(3);
        Outcome out = approve.invoke(new Invocation(
                phone(subject, List.of("pwd")),
                new Submission(Map.of("number", number), data),
                Map.of(),
                "f_phone",
                "acme",
                false));
        if (out instanceof Outcome.Ok ok) ok.effects().forEach(approve::commit);
        return out;
    }

    String poll(Shown shown, Context flow) {
        return (String) Objects.requireNonNull(qr.act(waiting(), flow, Map.of(), shown.data(), "poll", Map.of())
                .data()
                .get("status"));
    }

    Outcome redeem(Shown shown, String flowId, Env env) {
        return redeem.invoke(
                new Invocation(env, new Submission(Map.of(), shown.data()), Map.of(), flowId, "acme", false));
    }

    @Test
    void anApprovedCodeSignsTheWaitingDeviceInWithWhatThePhoneProved() {
        Shown shown = show();
        assertThat(poll(shown, WAITING)).isEqualTo("pending");
        assertThat(redeem(shown, WAITING.flowId(), waiting())).isEqualTo(new Outcome.Fail(QrSignIn.QR_NOT_APPROVED));
        assertThat(approveOn(shown.token(), "alice", shown.number())).isInstanceOf(Outcome.Ok.class);
        assertThat(poll(shown, WAITING)).isEqualTo("approved");
        assertThat(redeem(shown, WAITING.flowId(), waiting()))
                .isEqualTo(new Outcome.Ok(Map.of(), "alice", Set.of("pwd")));
        // A double submit of the same screen redeems again; no other flow ever can
        assertThat(redeem(shown, WAITING.flowId(), waiting())).isInstanceOf(Outcome.Ok.class);
        assertThat(redeem(shown, "f_other", waiting())).isNotInstanceOf(Outcome.Ok.class);
    }

    @Test
    void theWrongNumberRefusesTheCodeAtOnce() {
        Shown shown = show();
        long wrong = shown.number() == 99 ? 98 : shown.number() + 1;
        Context phoneFlow = new Context("f_phone", "acme", false, QrSignIn.hash(shown.token()));
        Map<String, Object> data = qrApprove.issue(phone("alice", List.of("pwd")), phoneFlow, Map.of(), Map.of());
        Outcome out = approve.invoke(new Invocation(
                phone("alice", List.of("pwd")),
                new Submission(Map.of("number", wrong), data),
                Map.of(),
                "f_phone",
                "acme",
                false));
        assertThat(out).isEqualTo(new Outcome.Fail("wrong_number"));
        assertThat(poll(shown, WAITING)).isEqualTo("refused");
        assertThat(redeem(shown, WAITING.flowId(), waiting())).isEqualTo(new Outcome.Fail(QrSignIn.QR_NOT_APPROVED));
        // A refused code shows a new one on the retry
        assertThat(qr.issue(waiting(), WAITING, Map.of(), shown.data()).get("token"))
                .isNotEqualTo(shown.data().get("token"));
    }

    @Test
    void aCodeExpiresAfterTwoMinutes() {
        Shown shown = show();
        assertThat(qr.issue(waiting(), WAITING, Map.of(), shown.data()))
                .containsEntry("token", shown.data().get("token"));
        clock.advance(QrSignIn.LIFETIME_MILLIS);
        assertThat(poll(shown, WAITING)).isEqualTo("expired");
        assertThat(redeem(shown, WAITING.flowId(), waiting())).isEqualTo(new Outcome.Fail(QrSignIn.QR_EXPIRED));
        assertThatThrownBy(() -> qrApprove.issue(
                        phone("alice", List.of("pwd")),
                        new Context("f_phone", "acme", false, QrSignIn.hash(shown.token())),
                        Map.of(),
                        Map.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(qr.issue(waiting(), WAITING, Map.of(), shown.data()).get("token"))
                .isNotEqualTo(shown.data().get("token"));
    }

    @Test
    void anApproverOtherThanTheIdentifiedAccountIsRefused() {
        Shown shown = show();
        approveOn(shown.token(), "bob", shown.number());
        assertThat(redeem(shown, WAITING.flowId(), waiting())).isEqualTo(new Outcome.Fail("wrong_account"));
    }

    @Test
    void theGrantKeepsOnlyTheTokensHash() {
        Shown shown = show();
        assertThat(grants.find(shown.token())).isEmpty();
        assertThat(grants.find(QrSignIn.hash(shown.token()))).hasValueSatisfying(g -> {
            assertThat(g.flowId()).isEqualTo(WAITING.flowId());
            assertThat(g.client()).containsEntry("client_name", "Acme Web");
        });
    }

    @Test
    void approvingNeedsASignedInSubject() {
        Shown shown = show();
        Env nobody = waiting();
        assertThatThrownBy(() -> qrApprove.issue(
                        nobody,
                        new Context("f_phone", "acme", false, QrSignIn.hash(shown.token())),
                        Map.of(),
                        Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void devices() {
        assertThat(QrSignIn.device(null)).isEmpty();
        assertThat(QrSignIn.device("Mozilla/5.0 (Windows NT 10.0) Firefox/131.0"))
                .isEqualTo("Firefox on Windows");
        assertThat(QrSignIn.device("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Safari/604.1"))
                .isEqualTo("Safari on iOS");
    }

    @Test
    void anApprovalAfterTheCodeExpiredWritesNothing() {
        Shown shown = show();
        Context phoneFlow = new Context("f_phone", "acme", false, QrSignIn.hash(shown.token()));
        Map<String, Object> data = qrApprove.issue(phone("alice", List.of("pwd")), phoneFlow, Map.of(), Map.of());
        // Kept across a retry of the same screen
        assertThat(qrApprove.issue(phone("alice", List.of("pwd")), phoneFlow, Map.of(), data))
                .containsEntry("choices", data.get("choices"));
        assertThat(qrApprove.resumable(phone("alice", List.of("pwd")), data)).isTrue();
        assertThat(qrApprove.resumable(waiting(), data)).isFalse();
        Outcome ok = approve.invoke(new Invocation(
                phone("alice", List.of("pwd")),
                new Submission(Map.of("number", shown.number()), data),
                Map.of(),
                "f_phone",
                "acme",
                false));
        clock.advance(QrSignIn.LIFETIME_MILLIS);
        assertThat(approve.commit(((Outcome.Ok) ok).effects().getFirst())).isEqualTo(QrSignIn.QR_EXPIRED);
        assertThat(approve.invoke(new Invocation(
                        phone("alice", List.of("pwd")),
                        new Submission(Map.of("number", shown.number()), data),
                        Map.of(),
                        "f_phone",
                        "acme",
                        false)))
                .isEqualTo(new Outcome.Fail(QrSignIn.QR_EXPIRED));
    }

    @Test
    void handlersRefuseWhatTheyCannotAnswer() {
        assertThatThrownBy(() -> redeem.invoke(
                        new Invocation(waiting(), new Submission(Map.of(), Map.of()), Map.of(), "f", "acme", false)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> approve.invoke(new Invocation(
                        phone("alice", List.of("pwd")),
                        new Submission(Map.of("number", 42), Map.of()),
                        Map.of(),
                        "f",
                        "acme",
                        false)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(approve.invoke(
                        new Invocation(waiting(), new Submission(Map.of(), Map.of()), Map.of(), "f", "acme", false)))
                .isEqualTo(new Outcome.Fail("unauthenticated"));
        assertThatThrownBy(() -> approve.commit(new faye.engine.Effect("qr.approve", Map.of())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> qr.issue(waiting(), Map.of(), Map.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> qrApprove.issue(waiting(), Map.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> qr.act(waiting(), WAITING, Map.of(), Map.of(), "poll", Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anotherFlowReadsNothingOfACode() {
        Shown shown = show();
        assertThat(poll(shown, new Context("f_other", "acme", false))).isEqualTo("expired");
        assertThat(qr.resumable(waiting(), shown.data())).isTrue();
        assertThat(qr.resumable(waiting(), Map.of("token", "t"))).isFalse();
    }

    @Test
    void theStoreApprovesOnceAndOnlyPendingCodes() {
        Shown shown = show();
        String hash = QrSignIn.hash(shown.token());
        long now = clock.millis();
        assertThat(grants.approve("missing", "alice", List.of("pwd"), now)).isFalse();
        assertThat(grants.approve(hash, "alice", List.of("pwd"), now)).isTrue();
        assertThat(grants.approve(hash, "alice", List.of("pwd"), now))
                .as("idempotent")
                .isTrue();
        assertThat(grants.approve(hash, "bob", List.of("pwd"), now)).isFalse();
        grants.refuse(hash);
        assertThat(grants.find(hash))
                .hasValueSatisfying(g -> assertThat(g.status()).isEqualTo(faye.engine.spi.QrGrants.Status.APPROVED));
        assertThat(grants.consume(hash, "f_other", now)).isEmpty();
        assertThat(grants.consume("missing", WAITING.flowId(), now)).isEmpty();
        assertThat(grants.consume(hash, WAITING.flowId(), now + QrSignIn.LIFETIME_MILLIS))
                .isEmpty();
    }

    @Test
    void moreDevices() {
        assertThat(QrSignIn.device("Mozilla/5.0 (Windows NT 10.0) Chrome/130.0 Edg/130.0"))
                .isEqualTo("Edge on Windows");
        assertThat(QrSignIn.device("Mozilla/5.0 (Linux; Android 14) Chrome/130.0"))
                .isEqualTo("Chrome on Android");
        assertThat(QrSignIn.device("Mozilla/5.0 (X11; Linux x86_64) Firefox/131.0"))
                .isEqualTo("Firefox on Linux");
        assertThat(QrSignIn.device("curl/8.0")).isEqualTo("A browser");
        assertThat(QrSignIn.device(42)).isEmpty();
    }
}
