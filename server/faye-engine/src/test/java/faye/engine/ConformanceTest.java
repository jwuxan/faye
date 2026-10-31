package faye.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import faye.host.mock.MockFlowHost;
import faye.ir.Hook;
import faye.ir.IrCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Runs every vector in {@code conformance/vectors} against this engine. */
class ConformanceTest {

    static final Path DIR = Path.of(System.getProperty("faye.repoRoot"), "conformance");
    static final ObjectMapper YAML = new YAMLMapper();
    static final ObjectMapper JSON = new ObjectMapper();

    /** Hand written vectors, then the ones generated from path enumeration ({@code faye-check}). */
    static Stream<Path> vectors() throws IOException {
        try (Stream<Path> generated = Files.walk(DIR.resolve("generated"))) {
            List<Path> all =
                    new ArrayList<>(Files.list(DIR.resolve("vectors")).sorted().toList());
            all.addAll(generated
                    .filter(p -> p.toString().endsWith(".yaml"))
                    .sorted()
                    .toList());
            return all.stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void vector(Path file) throws IOException {
        JsonNode v = YAML.readTree(file.toFile());
        JsonNode meta = YAML.readTree(DIR.resolve("handlers.yaml").toFile());
        String subject = v.path("subject").asText("alice");

        List<ScriptedHandler> fakes = new ArrayList<>();
        v.path("handlers").properties().forEach(e -> {
            JsonNode m = meta.get(e.getKey());
            assertThat(m).as("handler metadata for " + e.getKey()).isNotNull();
            Set<String> amr = new HashSet<>();
            m.path("amr").forEach(a -> amr.add(a.asText()));
            fakes.add(
                    new ScriptedHandler(e.getKey(), amr, m.path("binds_subject").asBoolean(), subject, e.getValue()));
        });

        var hooks = new ScriptedHooks(v.path("hooks"));
        var versions = new InMemoryFlowVersions();
        versions.publish(
                Engine.DEFAULT_TENANT,
                FlowVersion.of(IrCodec.readFlow(
                        Files.readString(DIR.resolve(v.path("ir").asText())))));
        var host = new MockFlowHost();
        HostKind kind =
                HostKind.valueOf(v.path("host").path("kind").asText("login").toUpperCase(java.util.Locale.ROOT));
        host.register(new HostRequest(
                kind,
                "challenge-1",
                map(v.path("host").path("client")),
                map(v.path("host").path("hydra"))));
        var tenant = v.path("tenant");
        var policy = new TenantPolicy(tenant.path("mfa_required").asBoolean(), null, null);
        var previews = new ScriptedPreviews(v.path("preview"), versions);
        var engine = new Engine(
                versions,
                new HandlerRegistry(fakes),
                new InMemoryFlowStore(),
                host,
                TenantPolicies.of(Map.of(Engine.DEFAULT_TENANT, policy)),
                hooks,
                previews);

        EngineResponse last = engine.start(kind, "challenge-1", Map.of());
        for (JsonNode event : v.path("events")) {
            if (event.has("expect_screen")) {
                assertThat(last).as(event.toString()).isInstanceOf(ScreenView.class);
                ScreenView screen = (ScreenView) last;
                assertThat(screen.screen().get("type"))
                        .isEqualTo(event.get("expect_screen").asText());
                if (event.has("expect_step")) {
                    assertThat(screen.step()).isEqualTo(event.get("expect_step").asText());
                }
                if (event.has("expect_identifier")) {
                    assertThat(screen.screen().get("identifier"))
                            .isEqualTo(event.get("expect_identifier").asText());
                }
                if (event.has("expect_error")) {
                    assertThat(screen.screen().get("errors"))
                            .isEqualTo(List.of(
                                    Map.of("code", event.get("expect_error").asText())));
                } else {
                    assertThat(screen.screen()).as("errors on " + event).doesNotContainKey("errors");
                }
                if (event.has("expect_attempts_left")) {
                    assertThat(screen.screen().get("attemptsLeft"))
                            .isEqualTo(event.get("expect_attempts_left").asInt());
                }
                if (event.has("expect_options")) {
                    assertThat(screen.screen().get("options"))
                            .isEqualTo(JSON.convertValue(event.get("expect_options"), List.class));
                }
                if (event.has("expect_passkey")) {
                    assertThat(screen.screen().containsKey("passkey"))
                            .isEqualTo(event.get("expect_passkey").asBoolean());
                }
            } else if (event.has("publish")) {
                versions.publish(
                        Engine.DEFAULT_TENANT,
                        FlowVersion.of(IrCodec.readFlow(Files.readString(
                                DIR.resolve(event.get("publish").asText())))));
            } else if (event.has("submit")) {
                ScreenView screen = (ScreenView) last;
                last = engine.submit(screen.flowId(), screen.nonce(), new Submission(map(event.get("submit"))));
            } else if (event.has("expect_result")) {
                JsonNode expected = event.get("expect_result");
                assertThat(last).isInstanceOf(Redirect.class);
                if (expected.path("succeed").asBoolean() && kind == HostKind.CONSENT) {
                    assertThat(host.rejected()).isEmpty();
                    assertThat(host.consented()).hasSize(1);
                    Consented c = host.consented().get(0).outcome();
                    assertThat(c.subject()).isEqualTo(expected.path("subject").asText());
                    assertThat(c.grantScope()).isEqualTo(JSON.convertValue(expected.path("grant_scope"), List.class));
                    if (expected.has("id_token")) {
                        assertThat(c.idTokenClaims()).isEqualTo(map(expected.get("id_token")));
                    }
                } else if (expected.path("succeed").asBoolean()) {
                    assertThat(host.rejected()).isEmpty();
                    assertThat(host.accepted()).hasSize(1);
                    Authenticated a = host.accepted().get(0).outcome();
                    assertThat(a.subject()).isEqualTo(expected.path("subject").asText());
                    assertThat(a.amr()).isEqualTo(JSON.convertValue(expected.path("amr"), List.class));
                    if (expected.has("acr")) {
                        assertThat(a.acr()).isEqualTo(expected.get("acr").asText());
                    }
                } else {
                    assertThat(host.accepted()).isEmpty();
                    assertThat(host.consented()).isEmpty();
                    assertThat(host.rejected()).hasSize(1);
                    assertThat(host.rejected().get(0).description())
                            .isEqualTo(expected.path("reason").asText());
                }
            } else {
                throw new AssertionError("unknown event " + event);
            }
        }
        fakes.forEach(f ->
                assertThat(f.script).as("unconsumed outcomes for " + f.name).isEmpty());
        hooks.scripts.forEach((id, script) ->
                assertThat(script).as("unconsumed outcomes for hook " + id).isEmpty());
        // Only preview clients' flows are traced
        List<String> expectedTrace = new ArrayList<>();
        v.path("expect_trace").forEach(e -> expectedTrace.add(e.asText()));
        assertThat(previews.trace).as("trace").isEqualTo(expectedTrace);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(JsonNode node) {
        return node.isMissingNode() ? Map.of() : JSON.convertValue(node, Map.class);
    }

    /**
     * Hooks by id, answering each run with the next scripted {@code {returns: raw}} or {@code
     * {throws: message}}. Every run must get a snapshot without {@code auth}.
     */
    static final class ScriptedHooks implements HookRunner {
        final Map<String, Deque<JsonNode>> scripts = new HashMap<>();

        ScriptedHooks(JsonNode script) {
            script.properties().forEach(e -> {
                Deque<JsonNode> runs = new ArrayDeque<>();
                e.getValue().forEach(runs::add);
                scripts.put(e.getKey(), runs);
            });
        }

        @Override
        public @Nullable Object run(String tenantId, Hook hook, Map<String, Object> ctx) {
            JsonNode next = scripts.getOrDefault(hook.id(), new ArrayDeque<>()).poll();
            if (next == null) throw new AssertionError("no scripted outcome left for hook " + hook.id());
            Set<String> keys = new HashSet<>(List.of("request", "client", "hydra", "tenant", "user", "vars"));
            if (ctx.containsKey("options")) keys.add("options");
            assertThat(ctx.keySet()).as("ctx of hook " + hook.id()).isEqualTo(keys);
            if (next.has("expect_options")) {
                assertThat(ctx.get("options")).isEqualTo(JSON.convertValue(next.get("expect_options"), List.class));
            }
            if (next.has("expect_request")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> request = (Map<String, Object>) ctx.get("request");
                assertThat(request).containsAllEntriesOf(map(next.get("expect_request")));
            }
            if (next.has("throws")) throw new HookException(next.get("throws").asText());
            if (!next.has("returns")) throw new AssertionError("bad hook outcome " + next);
            return JSON.convertValue(next.get("returns"), Object.class);
        }
    }

    /**
     * A preview client runs {@code draft} (refused without one) with the {@code request} overrides;
     * the trace is recorded as {@link TraceEvent#describe()}.
     */
    static final class ScriptedPreviews implements Previews {
        final @Nullable FlowVersion draft;
        final Map<String, Object> request;
        final List<String> trace = new ArrayList<>();
        final FlowVersions versions;

        ScriptedPreviews(JsonNode preview, FlowVersions versions) throws IOException {
            this.versions = versions;
            this.draft = preview.has("draft")
                    ? FlowVersion.of(IrCodec.readFlow(
                            Files.readString(DIR.resolve(preview.get("draft").asText()))))
                    : null;
            this.request = map(preview.path("request"));
        }

        @Override
        public Optional<FlowVersion> version(String tenantId) {
            if (draft != null) versions.store(draft);
            return Optional.ofNullable(draft);
        }

        @Override
        public Map<String, Object> request(String tenantId) {
            return request;
        }

        @Override
        public void trace(FlowState state, TraceEvent event) {
            trace.add(event.describe());
        }
    }

    static final class ScriptedHandler implements Handler {
        final String name;
        final Set<String> amr;
        final boolean binds;
        final String subject;
        final Deque<JsonNode> script = new ArrayDeque<>();

        ScriptedHandler(String name, Set<String> amr, boolean binds, String subject, JsonNode outcomes) {
            this.name = name;
            this.amr = amr;
            this.binds = binds;
            this.subject = subject;
            outcomes.forEach(script::add);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Set<String> amr() {
            return amr;
        }

        @Override
        public boolean bindsSubject() {
            return binds;
        }

        @Override
        public Outcome invoke(Env env, Submission input, Map<String, Object> args) {
            JsonNode next = script.poll();
            if (next == null) throw new AssertionError("no scripted outcome left for " + name);
            if (next.isTextual() && next.asText().equals("ok")) {
                return binds ? Outcome.subject(subject) : Outcome.ok();
            }
            if (next.has("ok")) {
                JsonNode ok = next.get("ok");
                Set<String> proven = null;
                if (ok.has("amr")) {
                    proven = new HashSet<>();
                    for (JsonNode a : ok.get("amr")) proven.add(a.asText());
                }
                return new Outcome.Ok(map(ok.path("user")), binds ? subject : null, proven);
            }
            if (next.has("fail")) return Outcome.fail(next.get("fail").asText());
            throw new AssertionError("bad outcome " + next);
        }
    }
}
