package faye.engine;

import static org.assertj.core.api.Assertions.assertThat;

import faye.host.mock.MockFlowHost;
import faye.ir.IrCodec;
import faye.screens.FixedIssuers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Frozen fixtures of every format this engine supports (ADR 0024, Versioning), one directory per
 * pair of IR format and state format ({@code ir1-state1}): a version's canonical bytes and a flow
 * state saved waiting on it. This engine must read them and finish the
 * flow. The current format's fixtures are written only with {@code FAYE_REGENERATE=1} and only when
 * missing, never rewritten; without them the build fails. Dropping support for a format means
 * deleting its directory in the change that does so.
 */
class CompatTest {

    static final Path COMPAT = Path.of(System.getProperty("faye.repoRoot"), "conformance/compat");
    static final String BINDER = "compat";

    static Stream<Path> formats() throws IOException {
        try (Stream<Path> dirs = Files.list(COMPAT)) {
            return dirs.filter(Files::isDirectory).sorted().toList().stream();
        }
    }

    /** The current format has fixtures: password-only, waiting on the password screen. */
    @Test
    void currentFormatHasFixtures() throws IOException {
        // Named for both formats it freezes: bumping either needs new fixtures
        Path dir = COMPAT.resolve("ir" + IrCodec.FORMAT + "-state" + FlowStateCodec.FORMAT);
        if (Files.exists(dir.resolve("state.json"))) return;
        assertThat(System.getenv("FAYE_REGENERATE"))
                .as("%s has no fixtures; create them once with FAYE_REGENERATE=1", dir)
                .isEqualTo("1");
        String flow = Files.readString(COMPAT.resolveSibling("flows/password-only.json"));
        String canonical = IrCodec.canonicalJson(IrCodec.readFlow(flow));
        var versions = new InMemoryFlowVersions();
        versions.publish(Engine.DEFAULT_TENANT, FlowVersion.of(IrCodec.readFlow(canonical)));
        var store = new InMemoryFlowStore();
        var host = new MockFlowHost();
        host.register(
                new HostRequest(HostKind.LOGIN, "compat", Map.of("client_id", "demo"), HostParams.fromMap(Map.of())));
        var engine = Engine.builder()
                .versions(versions)
                .handlers(handlers())
                .store(store)
                .host(host)
                .screens(FixedIssuers.registry())
                .build();
        var identifier = (ScreenView) engine.start(HostKind.LOGIN, "compat", Map.of(), BINDER);
        var password = (ScreenView) engine.submit(
                identifier.flowId(),
                BINDER,
                identifier.nonce(),
                new Submission(Map.of("identifier", "alice@example.com")));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("version.json"), canonical);
        Files.writeString(
                dir.resolve("state.json"),
                FlowStateCodec.write(store.load(password.flowId()).orElseThrow()) + "\n");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("formats")
    void aSavedFlowOfASupportedFormatResumes(Path dir) throws IOException {
        String canonical = Files.readString(dir.resolve("version.json"));
        FlowState state = FlowStateCodec.read(
                        Files.readString(dir.resolve("state.json")).trim())
                .orElseThrow(() -> new AssertionError("this engine no longer reads the state of " + dir));
        assertThat(IrCodec.hash(canonical)).as("the version's address").isEqualTo(state.versionHash());

        var versions = new InMemoryFlowVersions();
        versions.store(new FlowVersion(state.versionHash(), IrCodec.readFlow(canonical)));
        var store = new InMemoryFlowStore();
        store.create(state);
        var host = new MockFlowHost();
        host.register(new HostRequest(
                HostKind.LOGIN, state.hostRef(), Map.of("client_id", "demo"), HostParams.fromMap(Map.of())));
        var engine = Engine.builder()
                .versions(versions)
                .handlers(handlers())
                .store(store)
                .host(host)
                .screens(FixedIssuers.registry())
                .build();

        ScreenView waiting = engine.view(state.flowId(), BINDER);
        assertThat(waiting.screen()).containsEntry("type", "password");
        assertThat(engine.submit(state.flowId(), BINDER, waiting.nonce(), new Submission(Map.of("password", "x"))))
                .isInstanceOf(Redirect.class);
        assertThat(host.accepted()).singleElement().satisfies(a -> {
            assertThat(a.outcome().subject()).isEqualTo("alice");
            assertThat(a.outcome().amr()).isEqualTo(List.of("pwd"));
        });
    }

    static HandlerRegistry handlers() {
        return new HandlerRegistry(List.of(
                EngineTest.handler("identity.resolve", Set.of(), false, in -> Outcome.ok()),
                EngineTest.handler("password.verify", Set.of("pwd"), true, in -> Outcome.subject("alice"))));
    }
}
