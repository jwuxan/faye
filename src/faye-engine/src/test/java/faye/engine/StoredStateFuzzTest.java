package faye.engine;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import faye.engine.spi.EngineResponse;
import faye.engine.spi.FlowStore;
import faye.engine.spi.HostKind;
import faye.engine.spi.HostParams;
import faye.engine.spi.ScreenView;
import faye.engine.testing.FixedIssuers;
import faye.engine.testing.MockFlowHost;
import faye.ir.IrCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * F7 (ADR 0007): a stored flow state that is corrupt, from an older build, or edited in the
 * database fails closed. Whatever bytes a store holds for a flow, {@link Engine#view} and {@link
 * Engine#submit} end in a screen, a redirect or a {@link FlowException}, never another exception
 * (O1, boundary B4). Inputs mutate real states of the conformance flows, taken at each screen:
 * values of the wrong type, missing and extra fields, paths outside the IR, unknown frame tags,
 * another version's hash, huge revisions. Half the flows run under flow settings, so their states
 * carry an {@code expiry} to mutate (ADR 0035), and half do not.
 */
class StoredStateFuzzTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    static final Path CONFORMANCE = Path.of(System.getProperty("faye.repoRoot"), "src/conformance");
    static final List<String> FLOWS =
            List.of("passkey-totp", "two-credentials", "mfa-policy", "partner-consent", "forms");

    /** Real states, taken at each screen of the flows above. */
    static final List<String> STATES = new ArrayList<>();

    /** The flow settings the flows at even positions in {@link #FLOWS} run under. */
    static final TenantPolicy LIMITED = new TenantPolicy(
            false,
            null,
            null,
            java.util.Set.of(),
            null,
            false,
            Map.of(
                    faye.ir.Entry.Kind.AUTHENTICATE,
                    new FlowSettings(java.time.Duration.ofHours(1), java.time.Duration.ofMinutes(30))));

    static {
        for (int i = 0; i < FLOWS.size(); i++) collect(FLOWS.get(i), i % 2 == 0);
    }

    @FuzzTest(maxDuration = "60s")
    void storedState(FuzzedDataProvider data) {
        String base = STATES.get(data.consumeInt(0, STATES.size() - 1));
        String stored;
        if (data.consumeInt(0, 7) == 0) {
            stored = data.consumeRemainingAsString();
        } else {
            JsonNode tree = read(base);
            int n = data.consumeInt(1, 4);
            for (int i = 0; i < n; i++) tree = mutate(tree, data);
            stored = tree.toString();
        }
        exercise(FlowStateCodec.read(base).orElseThrow().flowId(), stored, data.consumeBoolean());
    }

    /** Every unmutated state reads, views and resumes. */
    @Test
    void realStatesResume() {
        // Unmutated, every state must view: a refusal here means the consistency check is wrong
        for (String state : STATES) {
            World w = new World();
            String flowId = FlowStateCodec.read(state).orElseThrow().flowId();
            w.store.raw.put(flowId, state);
            ScreenView view = w.engine.view(flowId, "binder");
            org.assertj.core.api.Assertions.assertThat(view.flowId()).isEqualTo(flowId);
        }
        org.assertj.core.api.Assertions.assertThat(STATES).hasSizeGreaterThan(8);
    }

    /** The F7 findings, as readable regressions: each corrupt state ends its flow as not found. */
    @Test
    void corruptStatesAreNotFound() {
        String base = STATES.get(0);
        String flowId = FlowStateCodec.read(base).orElseThrow().flowId();
        ObjectNode noKind = (ObjectNode) read(base);
        noKind.remove("kind");
        ObjectNode badPath = (ObjectNode) read(base);
        ((ObjectNode) badPath.get("awaiting"))
                .set("path", NODES.arrayNode().add(9).add(9));
        // A passkey registration for nobody: the passkey screen's spec says it cannot resume
        String passkey = STATES.stream()
                .filter(st -> "passkey"
                        .equals(read(st).path("awaiting").path("screen").asText()))
                .findFirst()
                .orElseThrow();
        ObjectNode register = (ObjectNode) read(passkey);
        ((ObjectNode) register.get("awaiting"))
                .set("data", NODES.objectNode().put("register", true).put("challenge", "c"));
        ((ObjectNode) register.get("env")).set("auth", NODES.objectNode().set("verified", NODES.arrayNode()));
        for (String corrupt : List.of("[]", "{", noKind.toString(), badPath.toString(), register.toString())) {
            World w = new World();
            String id = corrupt.equals(register.toString())
                    ? FlowStateCodec.read(passkey).orElseThrow().flowId()
                    : flowId;
            w.store.raw.put(id, corrupt);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> w.engine.view(id, "binder"))
                    .as(corrupt)
                    .isInstanceOfSatisfying(
                            FlowException.class,
                            e -> org.assertj.core.api.Assertions.assertThat(e.code())
                                    .isEqualTo(FlowException.Code.FLOW_NOT_FOUND));
        }
    }

    /** A state past its deadline is expired; an expiry that is not one fails closed as not found. */
    @Test
    void expiredStatesAreExpiredAndBadExpiriesNotFound() {
        String limited = STATES.stream()
                .filter(st -> read(st).hasNonNull("expiry"))
                .findFirst()
                .orElseThrow();
        String flowId = FlowStateCodec.read(limited).orElseThrow().flowId();
        ObjectNode past = (ObjectNode) read(limited);
        ((ObjectNode) past.get("expiry")).put("deadline", 0);
        ObjectNode idle = (ObjectNode) read(limited);
        ((ObjectNode) idle.get("expiry")).put("touched", 0);
        for (ObjectNode expired : List.of(past, idle)) {
            World w = new World();
            w.store.raw.put(flowId, expired.toString());
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> w.engine.view(flowId, "binder"))
                    .as(expired.toString())
                    .isInstanceOfSatisfying(
                            FlowException.class,
                            e -> org.assertj.core.api.Assertions.assertThat(e.code())
                                    .isEqualTo(FlowException.Code.FLOW_EXPIRED));
        }
        ObjectNode bad = (ObjectNode) read(limited);
        bad.put("expiry", "soon");
        World w = new World();
        w.store.raw.put(flowId, bad.toString());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> w.engine.view(flowId, "binder"))
                .isInstanceOfSatisfying(
                        FlowException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.code())
                                .isEqualTo(FlowException.Code.FLOW_NOT_FOUND));
    }

    static void exercise(String flowId, String stored, boolean submit) {
        World w = new World();
        w.store.raw.put(flowId, stored);
        try {
            ScreenView view = w.engine.view(flowId, "binder");
            if (submit) w.engine.submit(flowId, "binder", view.nonce(), new Submission(answer(view)));
        } catch (FlowException refused) {
            // declared
        }
    }

    static Map<String, Object> answer(ScreenView view) {
        return switch (String.valueOf(view.screen().get("type"))) {
            case "identifier" -> Map.of("identifier", "alice@example.com");
            case "password" -> Map.of("password", "pw");
            case "passkey" -> Map.of("passkey", Map.of("id", "k"));
            case "otp", "otp_enroll" -> Map.of("code", "123456");
            case "consent" -> Map.of("decision", "allow");
            case "method_picker" ->
                view.screen().get("options") instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m
                        ? Map.of("method", String.valueOf(m.get("id")))
                        : Map.of();
            default -> Map.of();
        };
    }

    // ---- mutation ----

    static JsonNode mutate(JsonNode root, FuzzedDataProvider data) {
        List<JsonNode> containers = new ArrayList<>();
        collectContainers(root, containers);
        JsonNode target = containers.get(data.consumeInt(0, containers.size() - 1));
        if (target instanceof ObjectNode o && !o.isEmpty()) {
            List<String> names = new ArrayList<>();
            o.fieldNames().forEachRemaining(names::add);
            String name = names.get(data.consumeInt(0, names.size() - 1));
            switch (data.consumeInt(0, 3)) {
                case 0 -> o.remove(name);
                case 1 -> o.set(data.consumeBoolean() ? name : data.consumeAsciiString(6), value(data));
                default -> o.set(name, value(data));
            }
        } else if (target instanceof ArrayNode a) {
            switch (data.consumeInt(0, 3)) {
                case 0 -> {
                    if (!a.isEmpty()) a.remove(data.consumeInt(0, a.size() - 1));
                }
                case 1 -> a.add(value(data));
                default -> {
                    if (!a.isEmpty()) a.set(data.consumeInt(0, a.size() - 1), value(data));
                }
            }
        }
        return root;
    }

    static void collectContainers(JsonNode node, List<JsonNode> out) {
        if (!node.isContainerNode()) return;
        out.add(node);
        node.forEach(child -> collectContainers(child, out));
    }

    static JsonNode value(FuzzedDataProvider data) {
        return switch (data.consumeInt(0, 11)) {
            case 0 -> NODES.nullNode();
            case 1 -> NODES.booleanNode(data.consumeBoolean());
            case 2 -> NODES.numberNode(data.consumeInt(-3, 40));
            case 3 -> NODES.numberNode(data.consumeLong());
            case 4 -> NODES.textNode(data.consumeString(12));
            case 5 ->
                NODES.textNode(data.pickValue(List.of(
                        "seq", "step", "retry", "choose", "when", "eval", "LOGIN", "CONSENT", "consent", "login",
                        "x")));
            case 6 -> NODES.arrayNode().add(data.consumeInt(-1, 9)).add(data.consumeInt(-1, 9));
            case 7 ->
                NODES.objectNode().put("t", data.pickValue(List.of("seq", "step", "retry", "choose", "eval", "x")));
            case 8 -> NODES.textNode("sha256:" + "0".repeat(64));
            case 9 -> NODES.numberNode(new java.math.BigInteger("99999999999999999999999"));
            case 10 -> NODES.arrayNode();
            default -> NODES.objectNode();
        };
    }

    static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- a world to resume states in ----

    /** A store that holds the bytes as a database would, read through the codec on every load. */
    static final class RawStore implements FlowStore {
        final Map<String, String> raw = new HashMap<>();

        @Override
        public Optional<FlowState> load(String flowId) {
            String json = raw.get(flowId);
            return json == null ? Optional.empty() : FlowStateCodec.read(json);
        }

        @Override
        public void create(FlowState state) {
            raw.put(state.flowId(), FlowStateCodec.write(state));
        }

        @Override
        public boolean replace(FlowState next) {
            raw.put(next.flowId(), FlowStateCodec.write(next));
            return true;
        }
    }

    static final class World {
        final InMemoryFlowVersions versions = new InMemoryFlowVersions();
        final RawStore store = new RawStore();
        final MockFlowHost host = new MockFlowHost();
        final Engine engine;

        World() {
            for (String name : FLOWS) {
                versions.store(
                        FlowVersion.of(IrCodec.readFlow(readFile(CONFORMANCE.resolve("flows/" + name + ".json")))));
            }
            versions.publish(
                    Engine.DEFAULT_TENANT,
                    FlowVersion.of(IrCodec.readFlow(readFile(CONFORMANCE.resolve("flows/passkey-totp.json")))));
            host.register(MockFlowHost.request(
                    HostKind.LOGIN, "c1", Map.of("client_id", "demo"), HostParams.fromMap(Map.of())));
            engine = Engine.builder()
                    .versions(versions)
                    .handlers(new HandlerRegistry(handlers()))
                    .store(store)
                    .host(host)
                    .screens(FixedIssuers.registry())
                    .build();
        }
    }

    /** Every catalog handler, always succeeding for alice. */
    static List<Handler> handlers() {
        List<Handler> out = new ArrayList<>();
        try {
            new YAMLMapper()
                    .readTree(CONFORMANCE.resolve("handlers.yaml").toFile())
                    .properties()
                    .forEach(e -> {
                        HandlerMeta meta = HandlerMeta.fromYaml(e.getValue());
                        out.add(new Handler() {
                            @Override
                            public String name() {
                                return e.getKey();
                            }

                            @Override
                            public HandlerMeta meta() {
                                return meta;
                            }

                            @Override
                            public Outcome invoke(Invocation call) {
                                @Nullable String subject = meta.bindsSubject() ? "alice" : null;
                                Map<String, Object> user = meta.identifies()
                                        ? Map.of(
                                                "identifier",
                                                "alice@example.com",
                                                "has_totp",
                                                true,
                                                "identified_by",
                                                "identifier")
                                        : Map.of();
                                return new Outcome.Ok(user, subject, null);
                            }
                        });
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** Drives a flow through its screens, keeping the stored state at each. */
    static void collect(String name, boolean limited) {
        InMemoryFlowVersions versions = new InMemoryFlowVersions();
        versions.publish(
                Engine.DEFAULT_TENANT,
                FlowVersion.of(IrCodec.readFlow(readFile(CONFORMANCE.resolve("flows/" + name + ".json")))));
        RawStore store = new RawStore();
        MockFlowHost host = new MockFlowHost();
        host.register(MockFlowHost.request(
                HostKind.LOGIN,
                "c1",
                Map.of("client_id", "demo"),
                HostParams.fromMap(Map.of("acr_values", List.of("faye:2fa")))));
        Engine engine = Engine.builder()
                .versions(versions)
                .handlers(new HandlerRegistry(handlers()))
                .store(store)
                .host(host)
                .tenants(limited ? Map.of(Engine.DEFAULT_TENANT, LIMITED) : Map.of())
                .screens(FixedIssuers.registry())
                .build();
        EngineResponse r = engine.start(HostKind.LOGIN, "c1", Map.of(), "binder");
        for (int i = 0; i < 6 && r instanceof ScreenView view; i++) {
            STATES.add(store.raw.get(view.flowId()));
            try {
                r = engine.submit(view.flowId(), "binder", view.nonce(), new Submission(answer(view)));
            } catch (FlowException e) {
                return;
            }
        }
    }

    static String readFile(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
