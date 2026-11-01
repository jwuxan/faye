package faye.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import faye.engine.spi.Authenticated;
import faye.engine.spi.Completion;
import faye.engine.spi.Consented;
import faye.engine.spi.FlowHost;
import faye.engine.spi.FlowStore;
import faye.engine.spi.Handoff;
import faye.engine.spi.HostKind;
import faye.engine.spi.HostParams;
import faye.engine.spi.HostRequest;
import faye.engine.spi.ScreenView;
import faye.ir.IrCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The engine embedded as a library, with nothing but its own SPIs: a host that hands off rather
 * than redirects, one screen of the embedder's own, its issuer and the handler that verifies what
 * the issuer issued. No Hydra, no built in screen, no Spring (ADR 0025).
 */
class EmbeddingTest {

    private static final String FLOW = """
            ["flow", 1, "embedded", 1,
              ["entry", "login", "authenticate",
                ["seq",
                  ["when", {"exists": "host.login_hint"}, ["set", "hinted", true]],
                  ["retry", 2, ["seq", ["screen", "code"], ["call", "code.verify"]]],
                  ["succeed"]]]]
            """;

    /** A host that started the flow by API and takes the outcome back as data. */
    static final class HandoffHost implements FlowHost {
        final List<Authenticated> accepted = new ArrayList<>();
        final List<String> rejected = new ArrayList<>();
        HostParams params = HostParams.NONE;

        @Override
        public HostRequest load(HostKind kind, String ref) {
            return new HostRequest(kind, ref, "embedded", false, Map.of("client_id", "app"), params);
        }

        @Override
        public Completion accept(HostRequest request, Authenticated outcome) {
            accepted.add(outcome);
            return new Handoff(Map.of("ref", request.ref(), "code", "one-time-" + accepted.size()));
        }

        @Override
        public Completion accept(HostRequest request, Consented outcome) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Completion reject(HostRequest request, String error, String description) {
            rejected.add(description);
            return new Handoff(Map.of("ref", request.ref(), "error", description));
        }
    }

    /** The embedder's own screen: asks for a code it issued out of band. */
    record CodeScreen() implements ScreenSpec {
        @Override
        public String type() {
            return "code";
        }

        @Override
        public boolean issued() {
            return true;
        }

        @Override
        public Map<String, Object> prepare(Env env, Map<String, Object> opts, Map<String, Object> previous) {
            return Map.of();
        }

        @Override
        public Map<String, Object> render(Env env, Map<String, Object> data) {
            return Map.of("type", "code");
        }

        @Override
        public boolean accepts(Submission input, Map<String, Object> data) {
            return input.payload().get("code") instanceof String;
        }
    }

    /** Issues the code the handler checks; a real one would ask an upstream service. */
    static final class CodeIssuer implements CredentialIssuer {
        final AtomicBoolean down = new AtomicBoolean();
        int issued;

        @Override
        public String screen() {
            return "code";
        }

        @Override
        public Map<String, Object> issue(Env env, Map<String, Object> opts, Map<String, Object> previous) {
            if (down.get()) throw new IllegalStateException("upstream unavailable");
            issued++;
            return Map.of("expected", "123456", "upstream_ref", "u-" + issued);
        }

        @Override
        public Map<String, Object> present(Env env, Map<String, Object> issued) {
            return Map.of("ref", issued.get("upstream_ref"));
        }
    }

    record CodeVerify() implements Handler {
        @Override
        public String name() {
            return "code.verify";
        }

        @Override
        public HandlerMeta meta() {
            return new HandlerMeta(Set.of("otp"), true, true, false, HandlerMeta.EffectKind.NONE, HandlerMeta.NO_ARGS);
        }

        @Override
        public Outcome invoke(Invocation call) {
            // The issued value arrives only as the submission's context, never through Env
            assertThat(List.of(
                            call.env().vars(),
                            call.env().host(),
                            call.env().user(),
                            call.env().tenant(),
                            call.env().request(),
                            call.env().client()))
                    .allSatisfy(m -> assertThat(m.toString()).doesNotContain("123456"));
            assertThat(call.tenant()).isEqualTo("embedded");
            return "123456".equals(call.input().context().get("expected"))
                            && "123456".equals(call.input().string("code"))
                    ? Outcome.subject("alice")
                    : Outcome.fail("invalid_code");
        }
    }

    /** A store whose next replace loses the revision race, as a concurrent submit would make it. */
    static final class RacingStore implements FlowStore {
        final InMemoryFlowStore inner = new InMemoryFlowStore();
        boolean loseNext;

        @Override
        public java.util.Optional<FlowState> load(String flowId) {
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

    private final HandoffHost host = new HandoffHost();
    private final CodeIssuer issuer = new CodeIssuer();
    private final RacingStore store = new RacingStore();

    private Engine engine() {
        var versions = new InMemoryFlowVersions();
        versions.publish("embedded", FlowVersion.of(IrCodec.readFlow(FLOW)));
        return Engine.builder()
                .versions(versions)
                .handlers(new HandlerRegistry(List.of(new CodeVerify())))
                .screens(new ScreenRegistry(List.of(new CodeScreen()), List.of(issuer)))
                .store(store)
                .host(host)
                .build();
    }

    @Test
    void aFinishedFlowHandsTheHostsResultToTheCaller() {
        Engine engine = engine();
        var screen = (ScreenView) engine.start(HostKind.LOGIN, "req-1", Map.of(), "b");
        // The public half only: what the issuer presents, never what it keeps for the handler
        assertThat(screen.screen()).isEqualTo(Map.of("type", "code", "ref", "u-1"));

        var done = engine.submit(screen.flowId(), "b", screen.nonce(), new Submission(Map.of("code", "123456")));

        assertThat(done).isEqualTo(new Handoff(Map.of("ref", "req-1", "code", "one-time-1")));
        assertThat(host.accepted).singleElement().satisfies(a -> {
            assertThat(a.subject()).isEqualTo("alice");
            assertThat(a.amr()).containsExactly("otp");
        });
    }

    @Test
    void aRetryIssuesAgainAndAWrongAnswerFailsClosedToTheHost() {
        Engine engine = engine();
        var first = (ScreenView) engine.start(HostKind.LOGIN, "req-1", Map.of(), "b");
        var again = (ScreenView)
                engine.submit(first.flowId(), "b", first.nonce(), new Submission(Map.of("code", "000000")));
        assertThat(again.screen()).containsEntry("ref", "u-2").containsKey("errors");

        var done = engine.submit(first.flowId(), "b", again.nonce(), new Submission(Map.of("code", "000000")));

        assertThat(done).isEqualTo(new Handoff(Map.of("ref", "req-1", "error", "invalid_code")));
        assertThat(host.accepted).isEmpty();
    }

    @Test
    void anIssuerThatFailsFailsTheStepClosed() {
        issuer.down.set(true);

        var done = engine().start(HostKind.LOGIN, "req-1", Map.of(), "b");

        assertThat(done).isEqualTo(new Handoff(Map.of("ref", "req-1", "error", Engine.ISSUER_ERROR)));
        assertThat(host.accepted).isEmpty();
    }

    @Test
    void aSubmitThatLosesTheRaceIsRefusedWithoutAccepting() {
        Engine engine = engine();
        var screen = (ScreenView) engine.start(HostKind.LOGIN, "req-1", Map.of(), "b");
        store.loseNext = true;

        assertThatThrownBy(() ->
                        engine.submit(screen.flowId(), "b", screen.nonce(), new Submission(Map.of("code", "123456"))))
                .isInstanceOfSatisfying(
                        FlowException.class, e -> assertThat(e.code()).isEqualTo(FlowException.Code.CONFLICT));
        assertThat(host.accepted).isEmpty();
    }

    @Test
    void flowsReadTheHostContractUnderItsWireNames() {
        host.params = HostParams.fromMap(Map.of("login_hint", "alice@example.com", "acr_values", List.of()));
        Engine engine = engine();
        var screen = (ScreenView) engine.start(HostKind.LOGIN, "req-1", Map.of(), "b");
        FlowState state = store.load(screen.flowId()).orElseThrow();

        assertThat(state.env().vars()).containsEntry("hinted", true);
        assertThat(state.env().host()).containsEntry("login_hint", "alice@example.com");
        assertThat(state.env().host().keySet()).isSubsetOf(HostParams.FIELDS);
    }

    @Test
    void theHostContractIsClosed() {
        assertThatThrownBy(() -> HostParams.fromMap(Map.of("prompt", "login")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prompt");
        assertThat(HostParams.readable("login_hint")).isTrue();
        assertThat(HostParams.readable("context.flowId")).isTrue();
        assertThat(HostParams.readable("login_hint.x")).isFalse();
        assertThat(HostParams.readable("unknown")).isFalse();
    }

    @Test
    void aChooseNeedsAPickerOnlyWhenItCanOfferSeveralOptions() {
        var flow = IrCodec.readFlow("""
                ["flow", 1, "choice", 1,
                  ["entry", "login", "authenticate",
                    ["seq",
                      ["choose", [["opt", "a", ["screen", "code"]], ["opt", "b", ["screen", "code"]]]],
                      ["call", "code.verify"],
                      ["succeed"]]]]
                """);
        var screens = new ScreenRegistry(List.of(new CodeScreen()), List.of(issuer));
        var handlers = new HandlerRegistry(List.of(new CodeVerify()));
        assertThat(Verifier.verify(flow, handlers, screens))
                .singleElement()
                .satisfies(
                        p -> assertThat(p.message()).contains("method_picker").contains("not registered"));

        var versions = new InMemoryFlowVersions();
        versions.publish("embedded", FlowVersion.of(flow));
        var engine = Engine.builder()
                .versions(versions)
                .handlers(handlers)
                .screens(screens)
                .store(store)
                .host(host)
                .build();
        assertThat(engine.start(HostKind.LOGIN, "req-1", Map.of(), "b"))
                .isEqualTo(new Handoff(Map.of("ref", "req-1", "error", Engine.FLOW_UNAVAILABLE)));
    }

    @Test
    void thePickerIsFilledByTheEngineAndTakesNoIssuer() {
        ScreenSpec picker = new ScreenSpec() {
            @Override
            public String type() {
                return ScreenRegistry.METHOD_PICKER;
            }

            @Override
            public boolean issued() {
                return true;
            }

            @Override
            public Map<String, Object> prepare(Env env, Map<String, Object> opts, Map<String, Object> previous) {
                return Map.of();
            }

            @Override
            public Map<String, Object> render(Env env, Map<String, Object> data) {
                return Map.of();
            }

            @Override
            public boolean accepts(Submission input, Map<String, Object> data) {
                return false;
            }
        };
        assertThatThrownBy(() -> new ScreenRegistry(List.of(picker), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot declare an issuer");
    }
}
