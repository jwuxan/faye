package faye.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import faye.engine.Engine;
import faye.engine.EngineResponse;
import faye.engine.FlowException;
import faye.engine.FlowState;
import faye.engine.FlowStore;
import faye.engine.FlowVersion;
import faye.engine.HandlerRegistry;
import faye.engine.HostKind;
import faye.engine.HostParams;
import faye.engine.InMemoryFlowStore;
import faye.engine.InMemoryFlowVersions;
import faye.engine.Redirect;
import faye.engine.ScreenView;
import faye.engine.Submission;
import faye.host.mock.MockFlowHost;
import faye.ir.IrCodec;
import faye.screens.BuiltinScreen;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ADR 0024 with the real identity handlers and store: a submit that loses the revision race leaves
 * no account change, and a TOTP code is good once across flows but a double submit of one flow is
 * not refused as a replay.
 */
class EffectsTest {

    static final Path FLOWS = Path.of(System.getProperty("faye.repoRoot"), "conformance/flows");
    static final String SECRET = "JBSWY3DPEHPK3PXP";
    static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    final UserStore users = new InMemoryUserStore();
    final MockFlowHost host = new MockFlowHost();
    final InMemoryFlowVersions versions = new InMemoryFlowVersions();
    final RacingStore store = new RacingStore();
    Engine engine;
    int challenges;

    /** A store where another submit wins the next revision race. */
    static final class RacingStore implements FlowStore {
        final InMemoryFlowStore inner = new InMemoryFlowStore();
        boolean loseNext;

        @Override
        public Optional<FlowState> load(String flowId) {
            return inner.load(flowId);
        }

        @Override
        public void create(FlowState state) {
            inner.create(state);
        }

        @Override
        public boolean replace(FlowState next) {
            if (loseNext) {
                loseNext = false;
                return false;
            }
            return inner.replace(next);
        }
    }

    @BeforeEach
    void setUp() {
        users.save(new User(
                "user-alice", "alice@example.com", null, true, Passwords.hash("right"), SECRET, -1, List.of(), false));
        users.save(new User(
                "user-bob", "bob@example.com", null, true, Passwords.hash("right"), null, -1, List.of(), false));
        var settings = new WebAuthnSettings("localhost", Set.of("http://localhost:3000"));
        engine = Engine.builder()
                .versions(versions)
                .handlers(
                        new HandlerRegistry(IdentityHandlers.create(users, settings, Clock.fixed(NOW, ZoneOffset.UTC))))
                .store(store)
                .host(host)
                .screens(BuiltinScreen.registry(IdentityIssuers.create()))
                .build();
    }

    @Test
    void aLostRaceEnrollsNothingAndTheWinnerEnrollsOnce() throws Exception {
        publish("enroll-totp.json");
        ScreenView s = start();
        s = screen(submit(s, Map.of("identifier", "bob@example.com")));
        s = screen(submit(s, Map.of("password", "right")));
        assertThat(s.screen()).containsEntry("type", "otp_enroll");
        String secret = (String) java.util.Objects.requireNonNull(s.screen().get("secret"));
        Map<String, Object> code = Map.of("code", Totp.code(Base32.decode(secret), NOW.getEpochSecond() / 30));

        store.loseNext = true;
        ScreenView enroll = s;
        assertThatThrownBy(() -> submit(enroll, code))
                .isInstanceOfSatisfying(
                        FlowException.class, e -> assertThat(e.code()).isEqualTo(FlowException.Code.CONFLICT));
        assertThat(users.bySubject("user-bob").orElseThrow().totpSecret())
                .as("the losing submit enrolled nothing")
                .isNull();

        assertThat(submit(s, code)).isInstanceOf(Redirect.class);
        assertThat(users.bySubject("user-bob").orElseThrow().totpSecret()).isEqualTo(secret);
        assertThat(host.accepted().get(0).outcome().amr()).containsExactly("pwd", "otp");
    }

    @Test
    void aTotpCodeIsGoodForOneFlowOnly() throws Exception {
        publish("passkey-totp.json");
        Map<String, Object> code = Map.of("code", Totp.code(Base32.decode(SECRET), NOW.getEpochSecond() / 30));
        ScreenView first = toOtp();
        ScreenView second = toOtp();

        assertThat(screen(submit(first, code)).step()).as("the code verified").isEqualTo("add-passkey");
        ScreenView replayed = screen(submit(second, code));
        assertThat(replayed.screen()).containsEntry("type", "otp");
        assertThat(replayed.screen().get("errors")).isEqualTo(List.of(Map.of("code", "invalid_code")));
    }

    @Test
    void aDoubleSubmitOfOneFlowIsAConflictNotAReplay() throws Exception {
        publish("passkey-totp.json");
        Map<String, Object> code = Map.of("code", Totp.code(Base32.decode(SECRET), NOW.getEpochSecond() / 30));
        ScreenView otp = toOtp();

        // The first request uses the step, then loses the race to the second, which uses it again
        store.loseNext = true;
        assertThatThrownBy(() -> submit(otp, code))
                .isInstanceOfSatisfying(
                        FlowException.class, e -> assertThat(e.code()).isEqualTo(FlowException.Code.CONFLICT));
        ScreenView next = screen(submit(otp, code));
        assertThat(next.step()).isEqualTo("add-passkey");
        assertThat(submit(next, Map.of("skip", true))).isInstanceOf(Redirect.class);
        assertThat(host.accepted().get(0).outcome().amr()).containsExactly("pwd", "otp");
    }

    ScreenView toOtp() {
        ScreenView s = start();
        s = screen(submit(s, Map.of("identifier", "alice@example.com")));
        s = screen(submit(s, Map.of("password", "right")));
        assertThat(s.screen()).containsEntry("type", "otp");
        return s;
    }

    void publish(String flow) throws Exception {
        versions.publish(
                Engine.DEFAULT_TENANT, FlowVersion.of(IrCodec.readFlow(Files.readString(FLOWS.resolve(flow)))));
    }

    ScreenView start() {
        String challenge = "c" + challenges++;
        host.register(MockFlowHost.request(
                HostKind.LOGIN, challenge, Map.of("client_id", "demo"), HostParams.fromMap(Map.of())));
        return screen(engine.start(HostKind.LOGIN, challenge, Map.of(), "binder"));
    }

    EngineResponse submit(ScreenView s, Map<String, Object> payload) {
        return engine.submit(s.flowId(), "binder", s.nonce(), new Submission(payload));
    }

    static ScreenView screen(EngineResponse r) {
        assertThat(r).isInstanceOf(ScreenView.class);
        return (ScreenView) r;
    }
}
