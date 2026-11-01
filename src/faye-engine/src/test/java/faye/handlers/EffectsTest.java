package faye.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import faye.engine.Engine;
import faye.engine.FlowException;
import faye.engine.FlowState;
import faye.engine.FlowVersion;
import faye.engine.HandlerRegistry;
import faye.engine.InMemoryFlowStore;
import faye.engine.InMemoryFlowVersions;
import faye.engine.Submission;
import faye.engine.spi.EngineResponse;
import faye.engine.spi.FlowStore;
import faye.engine.spi.FlowVersions;
import faye.engine.spi.HostKind;
import faye.engine.spi.HostParams;
import faye.engine.spi.Redirect;
import faye.engine.spi.ScreenView;
import faye.engine.testing.InMemoryCredentials;
import faye.engine.testing.MockFlowHost;
import faye.ir.IrCodec;
import faye.screens.BuiltinScreen;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ADR 0024 with the real built in handlers and credential store: a submit that loses the revision
 * race leaves no account change, and a TOTP code is good once across flows but a double submit of
 * one flow is not refused as a replay.
 */
class EffectsTest {

    static final Path FLOWS = Path.of(System.getProperty("faye.repoRoot"), "src/conformance/flows");

    final InMemoryCredentials creds = new InMemoryCredentials();
    final MockFlowHost host = new MockFlowHost();
    final FlowVersions versions = new InMemoryFlowVersions();
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
        creds.addUser("user-alice", "alice@example.com", "right", "SECRET");
        creds.addUser("user-bob", "bob@example.com", "right", null);
        engine = Engine.builder()
                .versions(versions)
                .handlers(new HandlerRegistry(BuiltinHandlers.create(creds, creds, creds, creds)))
                .store(store)
                .host(host)
                .screens(BuiltinScreen.registry(BuiltinIssuers.create(creds, creds)))
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
        Map<String, Object> code = Map.of("code", InMemoryCredentials.code(secret, InMemoryCredentials.NOW));

        store.loseNext = true;
        ScreenView enroll = s;
        assertThatThrownBy(() -> submit(enroll, code))
                .isInstanceOfSatisfying(
                        FlowException.class, e -> assertThat(e.code()).isEqualTo(FlowException.Code.CONFLICT));
        assertThat(creds.bySubject("user-bob").orElseThrow().hasTotp())
                .as("the losing submit enrolled nothing")
                .isFalse();

        assertThat(submit(s, code)).isInstanceOf(Redirect.class);
        assertThat(creds.bySubject("user-bob").orElseThrow().hasTotp()).isTrue();
        assertThat(host.accepted().get(0).outcome().amr()).containsExactly("pwd", "otp");
    }

    @Test
    void aTotpCodeIsGoodForOneFlowOnly() throws Exception {
        publish("passkey-totp.json");
        Map<String, Object> code = Map.of("code", InMemoryCredentials.code("SECRET", InMemoryCredentials.NOW));
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
        Map<String, Object> code = Map.of("code", InMemoryCredentials.code("SECRET", InMemoryCredentials.NOW));
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
