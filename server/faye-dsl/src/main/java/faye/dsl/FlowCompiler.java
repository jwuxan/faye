package faye.dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import faye.ir.Call;
import faye.ir.Choose;
import faye.ir.Fail;
import faye.ir.Flow;
import faye.ir.Hook;
import faye.ir.HookDef;
import faye.ir.IrCodec;
import faye.ir.IrException;
import faye.ir.Node;
import faye.ir.Predicate;
import faye.ir.Retry;
import faye.ir.Screen;
import faye.ir.Seq;
import faye.ir.SetVar;
import faye.ir.SourceRef;
import faye.ir.Step;
import faye.ir.Succeed;
import faye.ir.When;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/**
 * Compiles the YAML surface syntax to IR. Every IR node carries the YAML position it came from.
 *
 * <p>Supported surface:
 *
 * <pre>
 * flow: id
 * version: 1
 * hooks:
 *   hook-id:
 *     file: name.js | source: inline JavaScript
 *     capabilities: { http: [target] }     # optional
 *     timeout_ms: 200                      # optional
 * login | consent:
 *   steps:
 *     step-id:
 *       if: predicate                      # (when predicate ...)
 *       retry: 3                           # (retry 3 ...)
 *       set: { name: literal }             # (set name literal), first
 *       hook: hook-id                      # (hook hook-id hash)
 *       screen: type | { type: t, opt: literal }
 *       use: handler
 *       alternative: { option-id: body }   # (choose (opt option-id body) ...); or
 *       pick: hook-id                      # with alternative: (choose (hook ...) (opt ...) ...)
 *       steps: { step-id: body }           # nested steps
 *       fail: reason                       # last
 * </pre>
 *
 * A body needs at least one of set, hook, screen, use, alternative, steps, fail; its parts run in
 * the order above. Option bodies take the same keys. Predicates use the IR form ({@code eq},
 * {@code and}, ...). Each section compiles to {@code (seq (step ...) ... (succeed))}. A hook is
 * pinned by the hash of its definition ({@link HookDef}).
 */
public final class FlowCompiler {

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]*");
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*");

    private static final Pattern FILE = Pattern.compile("[a-z0-9][a-z0-9_-]*\\.js");

    /**
     * A compiled flow and the definitions of the hooks it references, by hash.
     *
     * @param hooks every hook the flow pins, keyed by {@link HookDef#hash()}
     */
    public record Compiled(Flow flow, Map<String, HookDef> hooks) {
        public Compiled {
            hooks = Map.copyOf(hooks);
        }
    }

    private final String origin;
    private final Function<String, Optional<String>> files;
    private final List<Diagnostic> errors = new ArrayList<>();
    /** Declared hooks by id, and the definitions they compiled to. */
    private final Map<String, Hook> declared = new LinkedHashMap<>();

    private final Map<String, HookDef> hooks = new LinkedHashMap<>();

    private FlowCompiler(String origin, Function<String, Optional<String>> files) {
        this.origin = origin;
        this.files = files;
    }

    /** @param origin file name used in source refs and diagnostics */
    public static Flow compile(String origin, String yaml) {
        return compileWithHooks(origin, yaml, name -> Optional.empty()).flow();
    }

    /**
     * @param files reads a hook's {@code file:} next to the flow; empty if there is no such file
     */
    public static Compiled compileWithHooks(String origin, String yaml, Function<String, Optional<String>> files) {
        FlowCompiler compiler = new FlowCompiler(origin, files);
        Flow flow = compiler.compile(yaml);
        return new Compiled(flow, compiler.hooks);
    }

    private Flow compile(String yaml) {
        org.yaml.snakeyaml.nodes.Node root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).compose(new StringReader(yaml));
        } catch (MarkedYAMLException e) {
            var mark = e.getProblemMark();
            throw new DslException(List.of(
                    new Diagnostic(new SourceRef(origin, mark.getLine() + 1, mark.getColumn() + 1), e.getProblem())));
        }
        if (!(root instanceof MappingNode top)) {
            throw new DslException(List.of(new Diagnostic(new SourceRef(origin, 1, 1), "expected a mapping")));
        }

        String id = null;
        Integer version = null;
        Map<String, Node> sections = new LinkedHashMap<>();
        // Hooks first, so steps can refer to them wherever they are declared
        NodeTuple hookSection = entries(top).get("hooks");
        if (hookSection != null) hooks(hookSection.getValueNode());
        for (var e : entries(top).entrySet()) {
            NodeTuple t = e.getValue();
            switch (e.getKey()) {
                case "flow" -> id = scalar(t.getValueNode(), ID, "flow id");
                case "version" -> version = version(t.getValueNode());
                case "hooks" -> {}
                case "login", "consent" -> sections.put(e.getKey(), section(t));
                default -> error(t.getKeyNode(), "unknown key '" + e.getKey() + "'");
            }
        }
        if (id == null) error(top, "missing 'flow'");
        if (version == null) error(top, "missing 'version'");
        if (!sections.containsKey("login")) error(top, "missing 'login' section");
        if (!errors.isEmpty() || id == null || version == null) throw new DslException(errors);
        return new Flow(id, version, sections);
    }

    private void hooks(org.yaml.snakeyaml.nodes.Node section) {
        if (!(section instanceof MappingNode m)) {
            error(section, "'hooks' must be a mapping of hook id to hook");
            return;
        }
        entries(m).forEach((id, t) -> {
            if (!ID.matcher(id).matches()) error(t.getKeyNode(), "hook id must match " + ID);
            if (t.getValueNode() instanceof MappingNode body) {
                HookDef def = hook(body);
                if (def != null) {
                    declared.put(id, new Hook(id, def.hash(), ref(t.getKeyNode())));
                    hooks.put(def.hash(), def);
                }
            } else {
                error(t.getValueNode(), "hook must be a mapping");
            }
        });
    }

    private @Nullable HookDef hook(MappingNode body) {
        String source = null;
        List<String> http = new ArrayList<>();
        int timeout = HookDef.DEFAULT_TIMEOUT_MS;
        for (var e : entries(body).entrySet()) {
            var value = e.getValue().getValueNode();
            switch (e.getKey()) {
                case "file" -> {
                    String name = scalar(value, FILE, "hook file");
                    Optional<String> text = FILE.matcher(name).matches() ? files.apply(name) : Optional.empty();
                    if (text.isPresent()) {
                        source = text.get();
                    } else if (FILE.matcher(name).matches()) {
                        error(value, "hook file '" + name + "' not found");
                    }
                }
                case "source" -> {
                    if (value instanceof ScalarNode s && s.getTag().equals(Tag.STR)) {
                        source = s.getValue();
                    } else {
                        error(value, "'source' must be a string of JavaScript");
                    }
                }
                case "capabilities" -> {
                    if (!(value instanceof MappingNode caps)) {
                        error(value, "'capabilities' must be a mapping");
                        continue;
                    }
                    entries(caps).forEach((cap, c) -> {
                        if (!cap.equals("http")) {
                            error(c.getKeyNode(), "unknown capability '" + cap + "'; hooks may have 'http'");
                        } else if (c.getValueNode() instanceof SequenceNode targets) {
                            targets.getValue().forEach(t -> http.add(scalar(t, HookDef.TARGET, "egress target")));
                        } else {
                            error(c.getValueNode(), "'http' must be a list of egress target names");
                        }
                    });
                }
                case "timeout_ms" -> {
                    if (value instanceof ScalarNode s
                            && s.getValue().matches("[1-9][0-9]{0,3}")
                            && Integer.parseInt(s.getValue()) <= HookDef.MAX_TIMEOUT_MS) {
                        timeout = Integer.parseInt(s.getValue());
                    } else {
                        error(value, "timeout_ms must be an integer from 1 to " + HookDef.MAX_TIMEOUT_MS);
                    }
                }
                default -> error(e.getValue().getKeyNode(), "unknown hook key '" + e.getKey() + "'");
            }
        }
        if (source == null) {
            if (!entries(body).containsKey("file") && !entries(body).containsKey("source")) {
                error(body, "hook needs 'file' or 'source'");
            }
            return null;
        }
        try {
            return new HookDef(source, http, timeout);
        } catch (IllegalArgumentException e) {
            error(body, String.valueOf(e.getMessage()));
            return null;
        }
    }

    /** The node for a reference to a declared hook, with the position of the reference. */
    private @Nullable Hook hookRef(org.yaml.snakeyaml.nodes.Node value) {
        String id = scalar(value, ID, "hook id");
        Hook hook = declared.get(id);
        if (hook == null) {
            if (ID.matcher(id).matches()) error(value, "unknown hook '" + id + "'; declare it under 'hooks'");
            return null;
        }
        return new Hook(id, hook.hash(), ref(value));
    }

    private Node section(NodeTuple section) {
        SourceRef at = ref(section.getKeyNode());
        List<Node> children = new ArrayList<>();
        if (section.getValueNode() instanceof MappingNode body) {
            for (var e : entries(body).entrySet()) {
                if (!e.getKey().equals("steps")) {
                    error(e.getValue().getKeyNode(), "unknown key '" + e.getKey() + "'");
                } else if (e.getValue().getValueNode() instanceof MappingNode steps) {
                    children.addAll(steps(steps));
                } else {
                    error(e.getValue().getValueNode(), "'steps' must be a mapping of step id to step");
                }
            }
        } else {
            error(section.getValueNode(), "section must be a mapping with 'steps'");
        }
        children.add(new Succeed(at));
        return new Seq(children, at);
    }

    private List<Node> steps(MappingNode steps) {
        List<Node> out = new ArrayList<>();
        entries(steps).forEach((stepId, step) -> {
            Node compiled = step(stepId, step);
            if (compiled != null) out.add(compiled);
        });
        return out;
    }

    private @Nullable Node step(String id, NodeTuple step) {
        SourceRef at = ref(step.getKeyNode());
        if (!ID.matcher(id).matches()) {
            error(step.getKeyNode(), "step id must match " + ID);
        }
        if (!(step.getValueNode() instanceof MappingNode body)) {
            error(step.getValueNode(), "step must be a mapping");
            return null;
        }
        Node inner = body(body, step.getKeyNode(), "step");
        return inner == null ? null : new Step(id, inner, at);
    }

    /** A step or option body: its parts in a fixed order, wrapped in retry, then in when. */
    private @Nullable Node body(MappingNode body, org.yaml.snakeyaml.nodes.Node owner, String what) {
        SourceRef at = ref(owner);
        List<Node> sets = new ArrayList<>();
        Hook hook = null;
        Hook pick = null;
        org.yaml.snakeyaml.nodes.Node pickAt = null;
        MappingNode alternative = null;
        Screen screen = null;
        Call call = null;
        Node nested = null;
        Fail fail = null;
        Predicate test = null;
        SourceRef testAt = null;
        int attempts = 0;
        SourceRef retryAt = null;
        for (var e : entries(body).entrySet()) {
            var value = e.getValue().getValueNode();
            switch (e.getKey()) {
                case "if" -> {
                    test = predicate(value);
                    testAt = ref(value);
                }
                case "retry" -> {
                    if (value instanceof ScalarNode s
                            && s.getValue().matches("[1-9][0-9]?")
                            && Integer.parseInt(s.getValue()) <= Retry.MAX_ATTEMPTS) {
                        attempts = Integer.parseInt(s.getValue());
                        retryAt = ref(value);
                    } else {
                        error(value, "retry must be an integer from 1 to " + Retry.MAX_ATTEMPTS);
                    }
                }
                case "set" -> {
                    if (value instanceof MappingNode vars) {
                        entries(vars)
                                .forEach((name, t) -> sets.add(new SetVar(
                                        scalar(t.getKeyNode(), KEY, "variable name"),
                                        literal(t.getValueNode()),
                                        ref(t.getKeyNode()))));
                    } else {
                        error(value, "'set' must be a mapping of variable name to literal");
                    }
                }
                case "hook" -> hook = hookRef(value);
                case "pick" -> {
                    pick = hookRef(value);
                    pickAt = value;
                }
                case "screen" -> screen = screen(value);
                case "use" -> call = new Call(scalar(value, NAME, "handler name"), Map.of(), ref(value));
                case "alternative", "steps" -> {
                    if (nested != null) {
                        error(e.getValue().getKeyNode(), "'alternative' and 'steps' cannot be combined");
                    } else if (!(value instanceof MappingNode m) || m.getValue().isEmpty()) {
                        error(value, "'" + e.getKey() + "' must be a non empty mapping");
                    } else if (e.getKey().equals("steps")) {
                        List<Node> children = steps(m);
                        if (!children.isEmpty()) {
                            nested = children.size() == 1 ? children.get(0) : new Seq(children, ref(value));
                        }
                    } else {
                        // Compiled once the whole body is read, since 'pick' may come after it
                        alternative = m;
                        nested = new Succeed(ref(m));
                    }
                }
                case "fail" -> fail = new Fail(scalar(value, ID, "fail reason"), ref(value));
                default -> error(e.getValue().getKeyNode(), "unknown " + what + " key '" + e.getKey() + "'");
            }
        }
        if (pickAt != null && alternative == null) {
            error(pickAt, "'pick' needs an 'alternative' to pick from");
        }
        if (alternative != null) nested = alternative(alternative, pick);
        List<Node> parts = new ArrayList<>(sets);
        for (Node part : new Node[] {hook, screen, call, nested, fail}) {
            if (part != null) parts.add(part);
        }
        if (parts.isEmpty()) {
            // An unknown hook was reported where it is named
            if (!entries(body).containsKey("hook")) {
                error(owner, what + " needs one of 'set', 'hook', 'screen', 'use', 'alternative', 'steps' or 'fail'");
            }
            return null;
        }
        Node inner = parts.size() == 1 ? parts.get(0) : new Seq(parts, at);
        if (retryAt != null) inner = new Retry(attempts, inner, retryAt);
        if (test != null && testAt != null) inner = new When(test, inner, testAt);
        return inner;
    }

    private @Nullable Node alternative(MappingNode options, @Nullable Hook pick) {
        List<Choose.Opt> out = new ArrayList<>();
        entries(options).forEach((id, option) -> {
            if (!ID.matcher(id).matches()) error(option.getKeyNode(), "option id must match " + ID);
            if (option.getValueNode() instanceof MappingNode body) {
                Node inner = body(body, option.getKeyNode(), "option");
                if (inner != null) out.add(new Choose.Opt(id, inner, ref(option.getKeyNode())));
            } else {
                error(option.getValueNode(), "option must be a mapping");
            }
        });
        return out.isEmpty() ? null : new Choose(pick, out, ref(options));
    }

    /** {@code type} or {@code { type: t, opt: literal, ... }}. */
    private Screen screen(org.yaml.snakeyaml.nodes.Node value) {
        if (!(value instanceof MappingNode m)) {
            return new Screen(scalar(value, ID, "screen type"), Map.of(), ref(value));
        }
        String type = null;
        Map<String, Object> opts = new LinkedHashMap<>();
        for (var e : entries(m).entrySet()) {
            if (e.getKey().equals("type")) {
                type = scalar(e.getValue().getValueNode(), ID, "screen type");
            } else if (ID.matcher(e.getKey()).matches()) {
                opts.put(e.getKey(), literal(e.getValue().getValueNode()));
            } else {
                error(e.getValue().getKeyNode(), "screen option must match " + ID);
            }
        }
        if (type == null) {
            error(value, "screen needs a 'type'");
            type = "invalid";
        }
        return new Screen(type, opts, ref(value));
    }

    /** Predicates have the IR shape, so they are read by the IR reader; errors point at the 'if'. */
    private @Nullable Predicate predicate(org.yaml.snakeyaml.nodes.Node node) {
        try {
            return IrCodec.readPredicate(json(node), "");
        } catch (IrException e) {
            error(node, "invalid predicate: " + e.getMessage());
            return null;
        }
    }

    private JsonNode json(org.yaml.snakeyaml.nodes.Node node) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        return switch (node) {
            case ScalarNode s -> {
                Object v = literal(s);
                yield switch (v) {
                    case Boolean b -> f.booleanNode(b);
                    case Integer i -> f.numberNode(i);
                    case Long l -> f.numberNode(l);
                    default -> f.textNode((String) v);
                };
            }
            case SequenceNode seq -> {
                ArrayNode out = f.arrayNode();
                seq.getValue().forEach(n -> out.add(json(n)));
                yield out;
            }
            case MappingNode m -> {
                ObjectNode out = f.objectNode();
                entries(m).forEach((k, t) -> out.set(k, json(t.getValueNode())));
                yield out;
            }
            default -> f.nullNode();
        };
    }

    /** A YAML scalar as a string, integer or boolean literal, by its resolved tag. */
    private Object literal(org.yaml.snakeyaml.nodes.Node node) {
        if (!(node instanceof ScalarNode s)) {
            error(node, "expected a string, integer or boolean");
            return "invalid";
        }
        if (s.getTag().equals(Tag.BOOL)) return Boolean.parseBoolean(s.getValue());
        if (s.getTag().equals(Tag.INT)) {
            try {
                long l = Long.parseLong(s.getValue());
                return l == (int) l ? (Object) (int) l : l;
            } catch (NumberFormatException e) {
                error(node, "integer out of range");
                return 0;
            }
        }
        return s.getValue();
    }

    /** Keys in document order; duplicate and non-string keys are reported. */
    private Map<String, NodeTuple> entries(MappingNode mapping) {
        Map<String, NodeTuple> out = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (NodeTuple t : mapping.getValue()) {
            if (!(t.getKeyNode() instanceof ScalarNode key)) {
                error(t.getKeyNode(), "keys must be strings");
            } else if (!seen.add(key.getValue())) {
                error(key, "duplicate key '" + key.getValue() + "'");
            } else {
                out.put(key.getValue(), t);
            }
        }
        return out;
    }

    private String scalar(org.yaml.snakeyaml.nodes.Node node, Pattern pattern, String what) {
        if (node instanceof ScalarNode s && pattern.matcher(s.getValue()).matches()) {
            return s.getValue();
        }
        error(node, what + " must match " + pattern);
        return "invalid";
    }

    private Integer version(org.yaml.snakeyaml.nodes.Node node) {
        if (node instanceof ScalarNode s && s.getValue().matches("[1-9][0-9]{0,8}")) {
            return Integer.parseInt(s.getValue());
        }
        error(node, "version must be a positive integer");
        return 0;
    }

    private SourceRef ref(org.yaml.snakeyaml.nodes.Node node) {
        var mark = node.getStartMark();
        return new SourceRef(origin, mark.getLine() + 1, mark.getColumn() + 1);
    }

    private void error(org.yaml.snakeyaml.nodes.Node node, String message) {
        errors.add(new Diagnostic(ref(node), message));
    }
}
