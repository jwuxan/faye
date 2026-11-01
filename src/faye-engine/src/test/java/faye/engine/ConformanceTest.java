package faye.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import faye.engine.spi.Authenticated;
import faye.engine.spi.Consented;
import faye.engine.spi.EngineResponse;
import faye.engine.spi.FlowStore;
import faye.engine.spi.FlowVersions;
import faye.engine.spi.HostKind;
import faye.engine.spi.HostParams;
import faye.engine.spi.HostRequest;
import faye.engine.spi.Redirect;
import faye.engine.spi.ScreenView;
import faye.engine.testing.FixedIssuers;
import faye.engine.testing.MockFlowHost;
import faye.hooks.HookException;
import faye.ir.Entry;
import faye.ir.Hook;
import faye.ir.IrCodec;
import faye.screens.BuiltinScreen;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Runs every vector in {@code conformance/vectors} against this engine. */
class ConformanceTest {

    static final Path DIR = Path.of(System.getProperty("faye.repoRoot"), "src/conformance");
    static final ObjectMapper YAML = new YAMLMapper();
    static final ObjectMapper JSON = new ObjectMapper();
    /** Reasons a request is refused with before any flow runs, which never mark the redirect failed. */
    static final Set<String> REFUSED_BEFORE_RUN = Set.of("flow_unavailable", "preview_unavailable");

    /** Hand written vectors, then the ones generated from path enumeration ({@code faye-check}). */
    static List<Path> vectors() throws IOException {
        try (Stream<Path> vectors = Files.list(DIR.resolve("vectors"));
                Stream<Path> generated = Files.walk(DIR.resolve("generated"))) {
            List<Path> all = new ArrayList<>(vectors.sorted().toList());
            all.addAll(generated
                    .filter(p -> p.toString().endsWith(".yaml"))
                    .sorted()
                    .toList());
            return all;
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
                .map(i -> new ScriptedIssuer(
                        i, v.path("issuers").path(i.screen()), v.path("actions").path(i.screen())))
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
                tenant.path("remember_me").asBoolean(),
                flowSettings(tenant.path("flows")));
        var previews = new ScriptedPreviews(v.path("preview"), versions);
        var store = new RacingStore();
        var clock = new SteppedClock();
        List<FailureSite> failures = new ArrayList<>();
        List<String> failedVersions = new ArrayList<>();
        var engine = Engine.builder()
                .clock(clock)
                .versions(versions)
                .handlers(new HandlerRegistry(fakes))
                .store(store)
                .host(host)
                .tenants(TenantPolicies.of(Map.of(Engine.DEFAULT_TENANT, policy)))
                .hooks(hooks)
                .previews(previews)
                .failures((state, site) -> {
                    failures.add(site);
                    failedVersions.add(state.versionHash());
                })
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
                if (event.has("expect_resend")) {
                    assertThat(screen.screen().containsKey("resendIn"))
                            .as("resend offered")
                            .isEqualTo(event.get("expect_resend").asBoolean());
                }
                if (event.has("expect_to")) {
                    assertThat(screen.screen().get("to"))
                            .isEqualTo(event.get("expect_to").asText());
                }
                if (event.has("expect_qr_status")) {
                    assertThat(screen.screen().get("status"))
                            .isEqualTo(event.get("expect_qr_status").asText());
                }
                if (event.has("expect_channel")) {
                    assertThat(screen.screen().get("channel"))
                            .isEqualTo(event.get("expect_channel").asText());
                }
                if (event.has("expect_required")) {
                    assertThat(screen.screen().containsKey("required"))
                            .as("required")
                            .isEqualTo(event.get("expect_required").asBoolean());
                }
                if (event.has("expect_remember")) {
                    assertThat(screen.screen().containsKey("remember"))
                            .isEqualTo(event.get("expect_remember").asBoolean());
                }
            } else if (event.has("wait")) {
                // Seconds pass before the next event (ADR 0035)
                clock.advance(Duration.ofSeconds(event.get("wait").asLong()));
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
            } else if (event.has("act")) {
                // A screen action (ADR 0032): `act: resend`, or `act: { action: send, input: {...} }`
                ScreenView screen = (ScreenView) last;
                JsonNode act = event.get("act");
                String action =
                        act.isTextual() ? act.asText() : act.path("action").asText();
                try {
                    last = engine.act(
                            screen.flowId(),
                            event.path("binder").asText("binder"),
                            event.path("nonce").asText(screen.nonce()),
                            action,
                            map(act.path("input")));
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
        issuers.forEach(i -> assertThat(i.actions)
                .as("unconsumed action outcomes for issuer " + i.screen())
                .isEmpty());
        // Only preview clients' flows are traced. A line may end with the node the event is about,
        // as @entry/path; without it, the event's at is not compared
        List<String> expectedTrace = new ArrayList<>();
        v.path("expect_trace").forEach(e -> expectedTrace.add(e.asText()));
        List<String> actualTrace = new ArrayList<>();
        for (int i = 0; i < previews.trace.size(); i++) {
            TraceEvent e = previews.trace.get(i);
            boolean located = i < expectedTrace.size() && expectedTrace.get(i).contains(" @");
            actualTrace.add(located ? e.describe() + " @" + e.at() : e.describe());
        }
        assertThat(actualTrace).as("trace").isEqualTo(expectedTrace);
        // Every event but start says where it happened, and that node exists in the version it ran
        @Nullable String ran = null;
        for (TraceEvent e : previews.trace) {
            if ("start".equals(e.event())) {
                ran = e.id();
                continue;
            }
            assertThat(e.at()).as("at of " + e.describe()).isNotNull();
            assertThat(nodeAt(versions, Objects.requireNonNull(ran), Objects.requireNonNull(e.at())))
                    .as("node at " + e.at())
                    .isTrue();
        }
        for (int i = 0; i < failures.size(); i++) {
            assertThat(nodeAt(versions, failedVersions.get(i), failures.get(i).site()))
                    .as("node at " + failures.get(i).site())
                    .isTrue();
        }
        // Every failure carries a fresh reference; expect_failure, when given, is the only one
        failures.forEach(f -> assertThat(f.reference()).matches(FailureSite.REFERENCE));
        assertThat(failures.stream().map(FailureSite::reference).distinct()).hasSameSizeAs(failures);
        if (v.has("expect_failure")) {
            JsonNode expected = v.get("expect_failure");
            if (expected.isTextual() && "none".equals(expected.asText())) {
                assertThat(failures).as("failures").isEmpty();
            } else {
                assertThat(failures).as("failures").hasSize(1);
                FailureSite f = failures.get(0);
                assertThat(f.entry())
                        .as("failure entry")
                        .isEqualTo(expected.path("entry").asText());
                assertThat(faye.ir.NodePaths.key(f.path()))
                        .as("failure path")
                        .isEqualTo(expected.path("path").asText());
                assertThat(f.step())
                        .as("failure step")
                        .isEqualTo(
                                expected.path("step").isTextual()
                                        ? expected.get("step").asText()
                                        : null);
                assertThat(f.code())
                        .as("failure code")
                        .isEqualTo(expected.path("code").asText());
            }
        }
    }

    /** Whether {@code at} names a node of the stored version {@code hash}. */
    static boolean nodeAt(FlowVersions versions, String hash, Site at) {
        faye.ir.Entry entry = versions.get(hash).orElseThrow().flow().entries().get(at.entry());
        return entry != null && faye.ir.NodePaths.find(entry.body(), at.path()).isPresent();
    }

    /**
     * The fixed issuer of a screen, whose issues are scripted in order: {@code fail} throws, as an
     * upstream that is down would; {@code ok}, and every issue once the script is used up, issues.
     * Its actions are scripted the same way, plus {@code {refused: code}}, an action the issuer
     * declined (a limit).
     */
    static final class ScriptedIssuer implements CredentialIssuer {
        final CredentialIssuer fixed;
        final ArrayDeque<String> script = new ArrayDeque<>();
        final ArrayDeque<JsonNode> actions = new ArrayDeque<>();

        ScriptedIssuer(CredentialIssuer fixed, JsonNode script, JsonNode actions) {
            this.fixed = fixed;
            script.forEach(s -> this.script.add(s.asText()));
            actions.forEach(this.actions::add);
        }

        @Override
        public Map<String, Object> issue(
                Env env, Context context, Map<String, Object> opts, Map<String, Object> previous) {
            return issue(env, opts, previous);
        }

        @Override
        public Acted act(
                Env env,
                Context context,
                Map<String, Object> opts,
                Map<String, Object> issued,
                String action,
                Map<String, Object> input) {
            JsonNode next = actions.poll();
            if (next != null && "fail".equals(next.asText())) {
                throw new IllegalStateException("scripted issuer failure");
            }
            if (next != null && next.has("refused")) {
                return Acted.refused(next.get("refused").asText());
            }
            return fixed.act(env, context, opts, issued, action, input);
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
     * the trace is recorded as it was handed over.
     */
    static final class ScriptedPreviews implements Previews {
        final @Nullable FlowVersion draft;
        final Map<String, Object> request;
        final List<TraceEvent> trace = new ArrayList<>();
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
            trace.add(event);
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

    /** {@code tenant.flows}: settings by entry kind, in seconds (ADR 0035). */
    private static Map<Entry.Kind, FlowSettings> flowSettings(JsonNode flows) {
        Map<Entry.Kind, FlowSettings> out = new java.util.EnumMap<>(Entry.Kind.class);
        flows.properties()
                .forEach(e -> out.put(
                        Entry.Kind.of(e.getKey())
                                .orElseThrow(() -> new IllegalArgumentException("entry kind " + e.getKey())),
                        new FlowSettings(
                                seconds(e.getValue().get("lifetime_s")),
                                seconds(e.getValue().get("idle_timeout_s")))));
        return out;
    }

    private static @Nullable Duration seconds(@Nullable JsonNode n) {
        return n == null ? null : Duration.ofSeconds(n.asLong());
    }

    /** A clock that moves only when a vector waits. */
    static final class SteppedClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
