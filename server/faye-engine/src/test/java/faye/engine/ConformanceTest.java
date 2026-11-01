package faye.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import faye.host.mock.MockFlowHost;
import faye.ir.Hook;
import faye.ir.IrCodec;
import faye.screens.BuiltinScreen;
import faye.screens.FixedIssuers;
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
    /** Reasons a request is refused with before any flow runs, which never mark the redirect failed. */
    static final Set<String> REFUSED_BEFORE_RUN = Set.of("flow_unavailable", "preview_unavailable");

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
        List<String> commits = new ArrayList<>();
        v.path("handlers")
                .fieldNames()
                .forEachRemaining(name -> assertThat(meta.has(name))
                        .as("handler metadata for " + name)
                        .isTrue());
        // Every handler of the catalog exists, as in production; only the scripted ones may be called
        meta.properties()
                .forEach(e -> fakes.add(new ScriptedHandler(
                        e.getKey(),
                        HandlerMeta.fromYaml(e.getValue()),
                        subject,
                        v.path("handlers").path(e.getKey()),
                        commits)));

        var hooks = new ScriptedHooks(v.path("hooks"));
        var issuers = FixedIssuers.all().stream()
                .map(i -> new ScriptedIssuer(i, v.path("issuers").path(i.screen())))
                .toList();
        var versions = new InMemoryFlowVersions();
        versions.publish(
                Engine.DEFAULT_TENANT,
                FlowVersion.of(IrCodec.readFlow(
                        Files.readString(DIR.resolve(v.path("ir").asText())))));
        var host = new MockFlowHost();
        HostKind kind =
                HostKind.valueOf(v.path("host").path("kind").asText("login").toUpperCase(java.util.Locale.ROOT));
        // The host decides the tenant and the preview flag; the vector says what it decided
        host.register(new HostRequest(
                kind,
                "challenge-1",
                v.path("host").path("tenant").isTextual()
                        ? v.path("host").path("tenant").asText()
                        : null,
                v.path("host").path("preview").asBoolean(false),
                map(v.path("host").path("client")),
                HostParams.fromMap(map(v.path("host").path("params")))));
        var tenant = v.path("tenant");
        var policy = new TenantPolicy(
                tenant.path("mfa_required").asBoolean(),
                null,
                null,
                Set.of(),
                null,
                tenant.path("remember_me").asBoolean());
        var previews = new ScriptedPreviews(v.path("preview"), versions);
        var store = new RacingStore();
        var engine = Engine.builder()
                .versions(versions)
                .handlers(new HandlerRegistry(fakes))
                .store(store)
                .host(host)
                .tenants(TenantPolicies.of(Map.of(Engine.DEFAULT_TENANT, policy)))
                .hooks(hooks)
                .previews(previews)
                .screens(BuiltinScreen.registry(issuers))
                .build();

        EngineResponse last = engine.start(kind, "challenge-1", Map.of(), "binder");
        @Nullable String refused = null;
        for (JsonNode event : v.path("events")) {
            if (!event.has("expect_refused")) {
                assertThat(refused)
                        .as("a refused submit must be followed by expect_refused")
                        .isNull();
            }
            if (event.has("expect_screen")) {
                assertThat(last).as(event.toString()).isInstanceOf(ScreenView.class);
                ScreenView screen = (ScreenView) last;
                assertThat(screen.screen().get("type"))
                        .isEqualTo(event.get("expect_screen").asText());
                if (event.has("expect_step")) {
                    assertThat(screen.step()).isEqualTo(event.get("expect_step").asText());
                }
                if (event.has("expect_path")) {
                    assertThat(screen.path()).isEqualTo(event.get("expect_path").asText());
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
                if (event.has("expect_password")) {
                    assertThat(screen.screen().containsKey("password"))
                            .isEqualTo(event.get("expect_password").asBoolean());
                }
                if (event.has("expect_remember")) {
                    assertThat(screen.screen().containsKey("remember"))
                            .isEqualTo(event.get("expect_remember").asBoolean());
                }
            } else if (event.has("publish")) {
                versions.publish(
                        Engine.DEFAULT_TENANT,
                        FlowVersion.of(IrCodec.readFlow(Files.readString(
                                DIR.resolve(event.get("publish").asText())))));
            } else if (event.has("submit")) {
                ScreenView screen = (ScreenView) last;
                // race: lost makes the store refuse this submit's save as if another had won; race:
                // { winner: payload } runs another answer of the same screen first, which wins
                store.loseNext = "lost".equals(event.path("race").asText());
                if (event.path("race").has("winner")) {
                    Submission winner = new Submission(map(event.path("race").get("winner")));
                    store.beforeNextReplace = () -> engine.submit(screen.flowId(), "binder", screen.nonce(), winner);
                }
                try {
                    last = engine.submit(
                            screen.flowId(),
                            event.path("binder").asText("binder"),
                            screen.nonce(),
                            new Submission(map(event.get("submit"))));
                    refused = null;
                } catch (FlowException e) {
                    refused = e.code().wireName();
                }
            } else if (event.has("expect_refused")) {
                assertThat(refused)
                        .as(event.toString())
                        .isEqualTo(event.get("expect_refused").asText());
                refused = null;
            } else if (event.has("expect_accepted")) {
                assertThat(host.accepted())
                        .as("host accepts")
                        .hasSize(event.get("expect_accepted").asInt());
            } else if (event.has("expect_commits")) {
                List<String> expected = new ArrayList<>();
                event.get("expect_commits").forEach(c -> expected.add(c.asText()));
                assertThat(commits).as("committed effects").isEqualTo(expected);
            } else if (event.has("expect_result")) {
                JsonNode expected = event.get("expect_result");
                assertThat(last).isInstanceOf(Redirect.class);
                // Only a sign in that ran and failed is marked; a refused consent, and a request
                // refused before any flow ran, go back to the app (ADR 0031)
                boolean beforeRun =
                        REFUSED_BEFORE_RUN.contains(expected.path("reason").asText());
                assertThat(((Redirect) last).failed())
                        .as("redirect marked failed")
                        .isEqualTo(!expected.path("succeed").asBoolean() && kind == HostKind.LOGIN && !beforeRun);
                if (expected.path("succeed").asBoolean() && kind == HostKind.CONSENT) {
                    assertThat(host.rejected()).isEmpty();
                    assertThat(host.consented()).hasSize(1);
                    Consented c = host.consented().get(0).outcome();
                    assertThat(c.subject()).isEqualTo(expected.path("subject").asText());
                    assertThat(c.grantScope()).isEqualTo(JSON.convertValue(expected.path("grant_scope"), List.class));
                    if (expected.has("user")) {
                        assertThat(c.user()).isEqualTo(map(expected.get("user")));
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
                    // Absent means the person did not ask to stay signed in
                    assertThat(a.remember())
                            .as("remember")
                            .isEqualTo(expected.path("remember").asBoolean(false));
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
        assertThat(refused)
                .as("a refused submit must be followed by expect_refused")
                .isNull();
        fakes.forEach(f ->
                assertThat(f.script).as("unconsumed outcomes for " + f.name).isEmpty());
        hooks.scripts.forEach((id, script) ->
                assertThat(script).as("unconsumed outcomes for hook " + id).isEmpty());
        issuers.forEach(i -> assertThat(i.script)
                .as("unconsumed outcomes for issuer " + i.screen())
                .isEmpty());
        // Only preview clients' flows are traced
        List<String> expectedTrace = new ArrayList<>();
        v.path("expect_trace").forEach(e -> expectedTrace.add(e.asText()));
        assertThat(previews.trace).as("trace").isEqualTo(expectedTrace);
    }

    /**
     * The fixed issuer of a screen, whose issues are scripted in order: {@code fail} throws, as an
     * upstream that is down would; {@code ok}, and every issue once the script is used up, issues.
     */
    static final class ScriptedIssuer implements CredentialIssuer {
        final CredentialIssuer fixed;
        final ArrayDeque<String> script = new ArrayDeque<>();

        ScriptedIssuer(CredentialIssuer fixed, JsonNode script) {
            this.fixed = fixed;
            script.forEach(s -> this.script.add(s.asText()));
        }

        @Override
        public String screen() {
            return fixed.screen();
        }

        @Override
        public Map<String, Object> issue(Env env, Map<String, Object> opts, Map<String, Object> previous) {
            if ("fail".equals(script.poll())) throw new IllegalStateException("scripted issuer failure");
            return fixed.issue(env, opts, previous);
        }

        @Override
        public Map<String, Object> present(Env env, Map<String, Object> issued) {
            return fixed.present(env, issued);
        }
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
            Set<String> keys = new HashSet<>(List.of("request", "client", "host", "tenant", "user", "vars"));
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

    /** A store where another submit can be made to win the next revision race. */
    static final class RacingStore implements FlowStore {
        final InMemoryFlowStore inner = new InMemoryFlowStore();
        boolean loseNext;

        @Nullable
        Runnable beforeNextReplace;

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
            Runnable first = beforeNextReplace;
            beforeNextReplace = null;
            if (first != null) first.run();
            return inner.replace(next);
        }
    }

    /**
     * Answers each call with the next scripted outcome: {@code ok}, {@code {ok: {user, amr, subject,
     * effects}}} or {@code {fail: code}}. An effect is scripted as its id, or {@code {id, fails: code}}
     * for one whose commit fails; committed effects are recorded as {@code handler:id}.
     */
    static final class ScriptedHandler implements Handler {
        final String name;
        final HandlerMeta meta;
        final String subject;
        final Deque<JsonNode> script = new ArrayDeque<>();
        final List<String> commits;

        ScriptedHandler(String name, HandlerMeta meta, String subject, JsonNode outcomes, List<String> commits) {
            this.name = name;
            this.meta = meta;
            this.subject = subject;
            this.commits = commits;
            outcomes.forEach(script::add);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public HandlerMeta meta() {
            return meta;
        }

        @Override
        public Outcome invoke(Invocation call) {
            JsonNode next = script.poll();
            if (next == null) throw new AssertionError("no scripted outcome left for " + name);
            boolean binds = meta.bindsSubject();
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
                List<Effect> effects = new ArrayList<>();
                ok.path("effects")
                        .forEach(e -> effects.add(new Effect(
                                name,
                                e.isTextual()
                                        ? Map.of("id", e.asText())
                                        : Map.of(
                                                "id",
                                                e.path("id").asText(),
                                                "fails",
                                                e.path("fails").asText()))));
                String who = binds ? ok.path("subject").asText(subject) : null;
                return new Outcome.Ok(map(ok.path("user")), who, proven, effects);
            }
            if (next.has("fail")) return Outcome.fail(next.get("fail").asText());
            throw new AssertionError("bad outcome " + next);
        }

        @Override
        public @Nullable String commit(Effect effect) {
            if (effect.data().get("fails") instanceof String code) return code;
            commits.add(name + ":" + effect.data().get("id"));
            return null;
        }
    }
}
