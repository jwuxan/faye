package faye.dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import faye.ir.Entry;
import faye.ir.Flow;
import faye.ir.HookDef;
import faye.ir.IrCodec;
import faye.ir.IrException;
import faye.ir.SourceRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Lowers the YAML surface syntax to Core IR. {@link YamlReader} reads the YAML into a positioned
 * {@link Doc}; lowering turns that into IR JSON, remembering which part of the document each JSON
 * node came from; {@link IrCodec} then checks the JSON against the one grammar ({@code
 * src/schema/ir.schema.json}) and every violation is reported at the YAML that produced it. The
 * compiler keeps no copy of the grammar: it checks only what exists in the surface alone (its
 * keys, hook declarations and files).
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
 * login:                                   # the sign in, from the first step to consent
 *   steps:
 *     step-id:                             # the first step is the identifier block
 *       if: predicate                      # (when predicate ...); on an option, its guard
 *       before: hook-id                    # (hook hook-id hash), before the rest
 *       set: { name: literal }             # (set name literal)
 *       block: id                          # (screen ...) (call ...), as the block says
 *       with: { setting: literal }         # the block's settings that differ from its defaults
 *       path: /challenge/name              # the block's screen's path option
 *       alternative: { option-id: body }   # (choose ((opt option-id body) ...)); or
 *       pick: hook-id                      # with alternative: (choose (...) {:pick (hook ...)})
 *       steps: { step-id: body }           # nested steps
 *       fail: reason
 *       after: hook-id                     # (hook hook-id hash), after the rest
 * </pre>
 *
 * A body is one kind of block, which decides the keys it takes: a built in block ({@code
 * block}, a {@link BlockSpec} the caller gives, which fixes its screen and handler), a choice
 * ({@code alternative}), a group ({@code steps}), {@code set} or {@code fail}. The identifier is the
 * first step and only there; an option is one credential block or a fail. Its parts run in the
 * order above. Predicates have the IR shape ({@code eq}, {@code and}, ...). A caller may give
 * namespace aliases, which predicates' paths are rewritten from (the app passes its host's legacy
 * spelling); the compiler itself knows none. Each entry compiles to {@code (seq (step ...) ...
 * (succeed))}. A hook is pinned by the hash of its definition ({@link HookDef}).
 *
 * <p>Consent is a step like any other: the steps of {@code login} up to the first one
 * that shows a consent screen or calls a consent handler ({@link Options}: the caller names them
 * from its catalogs; the compiler knows none) lower to the {@code authenticate} entry, and that step and
 * every step after it to the {@code consent} entry, since they run on the host's consent challenge,
 * after the sign in was accepted. The IR keeps the two entries.
 */
public final class FlowCompiler {

    /** A hook file name: surface only, it never reaches the IR. */
    private static final Pattern FILE = Pattern.compile("[a-z0-9][a-z0-9_-]*\\.js");

    /** The YAML key of the steps, from the first one to consent. */
    private static final String LOGIN = "login";

    /** The YAML key, and the entry id, of the steps that approve a sign in on another device (ADR 0032). */
    private static final String APPROVE = "approve";

    /** The id of the entry the consent steps lower to (the entry's name, not a screen's). */
    private static final String CONSENT = "consent";

    /**
     * What the compiler takes from its caller, since it knows no host and no catalog.
     *
     * @param aliases predicate path namespaces the YAML may use for an IR namespace (a host's legacy
     *     spelling of {@code host})
     * @param consentScreens screen types that belong to the consent entry: the first step showing
     *     one starts the consent steps
     * @param consentHandlers handlers that belong to the consent entry: the first step calling one
     *     starts the consent steps
     * @param blocks the blocks a step may be, by {@link BlockSpec#id}
     */
    public record Options(
            Map<String, String> aliases,
            Set<String> consentScreens,
            Set<String> consentHandlers,
            Map<String, BlockSpec> blocks) {
        /** No aliases, no consent steps and no blocks: every step lowers to the authenticate entry. */
        public static final Options NONE = new Options(Map.of(), Set.of(), Set.of(), Map.of());

        public Options {
            aliases = Map.copyOf(aliases);
            consentScreens = Set.copyOf(consentScreens);
            consentHandlers = Set.copyOf(consentHandlers);
            blocks = Map.copyOf(blocks);
        }

        /** These options with a tenant's defaults for its blocks' settings, by block id. */
        public Options withDefaults(Map<String, Map<String, Object>> defaults) {
            if (defaults.isEmpty()) return this;
            Map<String, BlockSpec> next = new LinkedHashMap<>(blocks);
            defaults.forEach((id, d) -> next.computeIfPresent(id, (k, b) -> b.withDefaults(d)));
            return new Options(aliases, consentScreens, consentHandlers, next);
        }

        /** Without blocks: a flow of choices, groups, sets and fails only. */
        public Options(Map<String, String> aliases, Set<String> consentScreens, Set<String> consentHandlers) {
            this(aliases, consentScreens, consentHandlers, Map.of());
        }
    }

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

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

    /** Where a JSON node came from, and what the author would call it. */
    private record Mark(SourceRef source, String what) {}

    /** A declared hook: its definition's hash and where it was declared. */
    private record Declared(String hash, SourceRef source) {}

    /** Hooks declared with an error already reported, so a reference to one is not reported again. */
    private final java.util.Set<String> broken = new java.util.HashSet<>();

    private final String origin;
    private final Function<String, Optional<String>> files;
    private final List<Diagnostic> errors = new ArrayList<>();
    private final Map<String, Declared> declared = new LinkedHashMap<>();
    private final Map<String, HookDef> hooks = new LinkedHashMap<>();
    /** Marks of JSON containers, and of scalars by their container and index. */
    private final IdentityHashMap<JsonNode, Mark> marks = new IdentityHashMap<>();

    private final IdentityHashMap<JsonNode, Map<Integer, Mark>> childMarks = new IdentityHashMap<>();

    /** Namespace aliases: a predicate path {@code <alias>.rest} lowers to {@code <namespace>.rest}. */
    private final Map<String, String> aliases;

    private final Options options;

    /** The id of the first step of {@code login}, which is the identifier. */
    private @Nullable String firstStep;

    /** Whether the identifier asks for the password, so no password block may. */
    private boolean passwordOnIdentifier;

    /** The steps being lowered are {@code approve}'s (ADR 0032). */
    private boolean approving;

    /** The caller's identifier block, which the first step is; null if it has none. */
    private final @Nullable BlockSpec identifier;

    /** The caller's password block, whose handler an identifier asking for the password calls. */
    private final @Nullable BlockSpec password;

    private FlowCompiler(String origin, Function<String, Optional<String>> files, Options options) {
        this.origin = origin;
        this.files = files;
        this.options = options;
        this.aliases = options.aliases();
        this.identifier = blockOf(options, BlockSpec.Role.IDENTIFIER);
        this.password = blockOf(options, BlockSpec.Role.PASSWORD);
    }

    /**
     * Compiles a flow's YAML to its IR.
     *
     * @param origin file name used in source refs and diagnostics
     */
    public static Flow compile(String origin, String yaml) {
        return compile(origin, yaml, Options.NONE);
    }

    /**
     * Compiles a flow's YAML to its IR.
     *
     * @param origin file name used in source refs and diagnostics
     */
    public static Flow compile(String origin, String yaml, Options options) {
        return compileWithHooks(origin, yaml, name -> Optional.empty(), options).flow();
    }

    /**
     * Compiles a flow's YAML to its IR, resolving hook {@code file:} references.
     *
     * @param files reads a hook's {@code file:} next to the flow; empty if there is no such file
     */
    public static Compiled compileWithHooks(String origin, String yaml, Function<String, Optional<String>> files) {
        return compileWithHooks(origin, yaml, files, Options.NONE);
    }

    /**
     * Compiles a flow's YAML to its IR, resolving hook {@code file:} references.
     *
     * @param files reads a hook's {@code file:} next to the flow; empty if there is no such file
     * @param options what the compiler takes from its caller
     */
    public static Compiled compileWithHooks(
            String origin, String yaml, Function<String, Optional<String>> files, Options options) {
        FlowCompiler compiler = new FlowCompiler(origin, files, options);
        Flow flow = compiler.compile(yaml);
        return new Compiled(flow, compiler.hooks);
    }

    /** The IR JSON the YAML lowers to, before any check; for golden tests and tools. */
    static JsonNode lower(String origin, String yaml) {
        return lower(origin, yaml, Options.NONE);
    }

    /** As {@link #lower(String, String)}, with the caller's options. */
    static JsonNode lower(String origin, String yaml, Options options) {
        FlowCompiler compiler = new FlowCompiler(origin, name -> Optional.empty(), options);
        YamlReader.Read read = YamlReader.read(origin, yaml);
        compiler.errors.addAll(read.errors());
        ArrayNode json = compiler.lower(read.doc());
        if (!compiler.errors.isEmpty()) throw new DslException(compiler.sorted(compiler.errors));
        return json;
    }

    private Flow compile(String yaml) {
        YamlReader.Read read = YamlReader.read(origin, yaml);
        errors.addAll(read.errors());
        ArrayNode json = lower(read.doc());
        Map<String, Mark> table = pointers(json);
        List<Diagnostic> all = new ArrayList<>(errors);
        // The grammar has something to say only once the surface gave an id, a version and an entry
        if (json.size() >= 5) {
            // A value the surface already refused is not reported again by the grammar
            java.util.Set<SourceRef> reported = new java.util.HashSet<>();
            errors.forEach(d -> reported.add(d.source()));
            for (IrException e : IrCodec.errors(json)) {
                Diagnostic d = diagnostic(table, e);
                if (!reported.contains(d.source())) all.add(d);
            }
        }
        if (!all.isEmpty()) throw new DslException(sorted(all));
        try {
            return IrCodec.readFlow(json, at -> mark(table, at).source());
        } catch (IrException e) {
            throw new DslException(List.of(diagnostic(table, e)));
        }
    }

    // ---- lowering ----

    private ArrayNode lower(Doc doc) {
        ArrayNode flow = JSON.arrayNode().add("flow").add(IrCodec.FORMAT);
        if (!(doc instanceof Doc.Map top)) {
            error(doc.source(), "expected a mapping");
            return flow;
        }
        mark(flow, top.source(), "flow");
        // Hooks first, so steps can refer to them wherever they are declared
        Doc.Entry hookSection = top.entries().get("hooks");
        if (hookSection != null) hooks(hookSection.value());
        Doc.Entry id = top.entries().get("flow");
        Doc.Entry version = top.entries().get("version");
        if (id == null) error(top.source(), "missing 'flow'");
        if (version == null) error(top.source(), "missing 'version'");
        if (id == null || version == null) return flow;
        flow.add(scalar(id.value()));
        markChild(flow, 2, id.value().source(), "flow id");
        flow.add(scalar(version.value()));
        markChild(flow, 3, version.value().source(), "version");

        top.entries().forEach((key, e) -> {
            switch (key) {
                case "flow", "version", "hooks" -> {}
                case LOGIN -> entries(e).forEach(flow::add);
                case APPROVE -> {
                    approving = true;
                    passwordOnIdentifier = false;
                    java.util.Map<String, Doc.Entry> steps = steps(e);
                    firstStep = steps.keySet().stream().findFirst().orElse(null);
                    if (firstStep == null) error(e.key(), "'approve' needs a step");
                    flow.add(entry(APPROVE, Entry.Kind.APPROVE, e.key(), steps));
                    approving = false;
                    passwordOnIdentifier = false;
                }
                case CONSENT ->
                    error(e.key(), "consent is a step of 'login' now: move these steps to the end of 'login.steps'");
                default -> error(e.key(), "unknown key '" + key + "'");
            }
        });
        if (!top.entries().containsKey(LOGIN)) {
            error(top.source(), "missing 'login'");
        }
        // Only a flow with an approve entry is format 2 (ADR 0032)
        for (int i = 4; i < flow.size(); i++) {
            if (Entry.Kind.APPROVE.wireName().equals(flow.get(i).path(2).asText())) {
                flow.set(1, JSON.numberNode(IrCodec.APPROVE_FORMAT));
            }
        }
        return flow;
    }

    /** A section's {@code steps}, reporting any other key. */
    private java.util.Map<String, Doc.Entry> steps(Doc.Entry section) {
        if (!(section.value() instanceof Doc.Map body)) {
            error(section.value().source(), "an entry must be a mapping with 'steps'");
            return java.util.Map.of();
        }
        java.util.Map<String, Doc.Entry> out = new LinkedHashMap<>();
        for (var e : body.entries().entrySet()) {
            if (!e.getKey().equals("steps")) {
                error(e.getValue().key(), "unknown key '" + e.getKey() + "'");
            } else if (e.getValue().value() instanceof Doc.Map m) {
                out.putAll(m.entries());
            } else {
                error(e.getValue().value().source(), "'steps' must be a mapping of step id to step");
            }
        }
        return out;
    }

    /**
     * The entries {@code login} lowers to: {@code authenticate} with the steps before the first one
     * that records consent, and {@code consent} with that step and the rest, if there is one.
     */
    private List<JsonNode> entries(Doc.Entry login) {
        Doc.@Nullable Map steps = null;
        if (login.value() instanceof Doc.Map body) {
            for (var e : body.entries().entrySet()) {
                if (!e.getKey().equals("steps")) {
                    error(e.getValue().key(), "unknown key '" + e.getKey() + "'");
                } else if (e.getValue().value() instanceof Doc.Map m) {
                    steps = m;
                } else {
                    error(e.getValue().value().source(), "'steps' must be a mapping of step id to step");
                }
            }
        } else {
            error(login.value().source(), "an entry must be a mapping with 'steps'");
        }
        firstStep = steps == null
                ? null
                : steps.entries().keySet().stream().findFirst().orElse(null);
        if (firstStep == null && login.value() instanceof Doc.Map) {
            error(
                    login.key(),
                    identifier == null
                            ? "'login' needs a step"
                            : "'login' needs its first step, the " + identifier.title() + " ('block: " + identifier.id()
                                    + "')");
        }
        java.util.Map<String, Doc.Entry> signIn = new LinkedHashMap<>();
        java.util.Map<String, Doc.Entry> consent = new LinkedHashMap<>();
        Doc.@Nullable Entry first = null;
        if (steps != null) {
            for (var e : steps.entries().entrySet()) {
                if (first == null && recordsConsent(e.getValue().value())) first = e.getValue();
                (first == null ? signIn : consent).put(e.getKey(), e.getValue());
            }
        }
        List<JsonNode> out = new ArrayList<>();
        out.add(entry(LOGIN, Entry.Kind.AUTHENTICATE, login.key(), signIn));
        if (first != null) out.add(entry(CONSENT, Entry.Kind.CONSENT, first.key(), consent));
        return out;
    }

    /**
     * Whether a step asks for consent: its body, or a body nested in its {@code alternative} or
     * {@code steps}, shows the consent screen or calls the consent handler. Only those keys are
     * looked at, so a literal elsewhere (a {@code set} value) never moves the split.
     */
    private boolean recordsConsent(Doc doc) {
        if (!(doc instanceof Doc.Map m)) return false;
        for (var e : m.entries().entrySet()) {
            Doc value = e.getValue().value();
            boolean asks = switch (e.getKey()) {
                case "block" ->
                    value instanceof Doc.Scalar s
                            && s.value() instanceof String id
                            && consent(options.blocks().get(id));
                case "alternative", "steps" ->
                    value instanceof Doc.Map nested
                            && nested.entries().values().stream().anyMatch(n -> recordsConsent(n.value()));
                default -> false;
            };
            if (asks) return true;
        }
        return false;
    }

    /** {@code (entry id kind (seq (step ...) ... (succeed)))}, all at {@code at}. */
    private ArrayNode entry(String id, Entry.Kind kind, SourceRef at, java.util.Map<String, Doc.Entry> steps) {
        ArrayNode entry = JSON.arrayNode().add("entry").add(id).add(kind.wireName());
        ArrayNode seq = JSON.arrayNode().add("seq");
        mark(seq, at, "steps");
        steps(new Doc.Map(steps, at), false).forEach(seq::add);
        ArrayNode succeed = JSON.arrayNode().add("succeed");
        mark(succeed, at, "succeed");
        entry.add(seq.add(succeed));
        mark(entry, at, "'" + id + "'");
        return entry;
    }

    /** Where a body sits, which decides the blocks it may be. */
    private enum Where {
        /** The first step of {@code login}: the identifier. */
        FIRST,
        /** Any other step of {@code login}. */
        STEP,
        /** A step of a group. */
        NESTED,
        /** An option of a choice. */
        OPTION
    }

    /** What a body does, which decides the keys it takes. */
    private enum Kind {
        BLOCK,
        CHOOSE,
        GROUP,
        SET,
        FAIL
    }

    /** A body's kind and, for a block's step, the block. */
    private record Shape(Kind kind, @Nullable BlockSpec block) {

        BlockSpec.@Nullable Role role() {
            return block == null ? null : block.role();
        }

        String named() {
            if (block != null) return article(block.title()) + " block";
            return switch (kind) {
                case BLOCK -> "a block";
                case CHOOSE -> "a Choose block";
                case GROUP -> "a Group block";
                case SET -> "a Set block";
                case FAIL -> "a Fail block";
            };
        }

        /** Whether the body takes {@code key}: hooks around any block but a fail, which nothing follows. */
        boolean takes(String key) {
            if ("after".equals(key)) return kind != Kind.FAIL;
            if ("if".equals(key)) return role() != BlockSpec.Role.IDENTIFIER;
            if ("before".equals(key)) return true;
            return switch (kind) {
                case BLOCK -> "block".equals(key) || "path".equals(key) || ("with".equals(key) && hasSettings());
                case CHOOSE -> "alternative".equals(key) || "pick".equals(key);
                case GROUP -> "steps".equals(key);
                case SET -> "set".equals(key);
                case FAIL -> "fail".equals(key);
            };
        }

        private boolean hasSettings() {
            return block == null || !block.settings().isEmpty();
        }

        /** The keys the body takes, in the order the surface documents them, for a diagnostic. */
        String keys() {
            return BODY_ORDER.stream()
                    .filter(this::takes)
                    .map(k -> "'" + k + "'")
                    .collect(java.util.stream.Collectors.joining(", "));
        }

        /** Whether the body may be an option of a choice: one credential, or a fail. */
        boolean option() {
            BlockSpec.Role role = role();
            return kind == Kind.FAIL || (kind == Kind.BLOCK && (role == null || role.option()));
        }
    }

    /** "an Identifier", "a Password". */
    private static String article(String title) {
        return ("AEIOU".indexOf(title.charAt(0)) >= 0 ? "an " : "a ") + title;
    }

    /** The keys a step or option may have, in the order the surface documents them. */
    private static final List<String> BODY_ORDER =
            List.of("if", "before", "set", "block", "with", "path", "alternative", "pick", "steps", "fail", "after");

    private static final Set<String> BODY_KEYS = Set.copyOf(BODY_ORDER);

    private List<JsonNode> steps(Doc.Map steps, boolean nested) {
        List<JsonNode> out = new ArrayList<>();
        steps.entries().forEach((id, step) -> {
            if (!(step.value() instanceof Doc.Map body)) {
                error(step.value().source(), "step must be a mapping");
                return;
            }
            Where where = nested ? Where.NESTED : id.equals(firstStep) ? Where.FIRST : Where.STEP;
            Lowered inner = body(body, step.key(), where);
            if (inner == null) return;
            ArrayNode node = JSON.arrayNode().add("step").add(id).add(inner.node());
            mark(node, step.key(), "step");
            markChild(node, 1, step.key(), "step id");
            out.add(node);
        });
        return out;
    }

    /** A lowered body, and for an option the guard its {@code if} became. */
    private record Lowered(JsonNode node, @Nullable JsonNode guard) {}

    /** The kind of a body, or null if it has none of the keys that decide one. */
    private @Nullable Shape shape(Doc.Map body) {
        Set<String> keys = body.entries().keySet();
        Doc.Entry at = body.entries().get("block");
        if (at != null) {
            Doc id = at.value();
            return new Shape(
                    Kind.BLOCK,
                    id instanceof Doc.Scalar s && s.value() instanceof String b
                            ? options.blocks().get(b)
                            : null);
        }
        if (keys.contains("alternative")) return new Shape(Kind.CHOOSE, null);
        if (keys.contains("steps")) return new Shape(Kind.GROUP, null);
        if (keys.contains("set")) return new Shape(Kind.SET, null);
        if (keys.contains("fail")) return new Shape(Kind.FAIL, null);
        return null;
    }

    /**
     * A step or option body: one kind of block, its parts in a fixed order, a block's attempts
     * around its own parts, then its {@code before} and {@code after} hooks, then in when (a step)
     * or guarded (an option).
     */
    private @Nullable Lowered body(Doc.Map body, SourceRef owner, Where where) {
        String what = where == Where.OPTION ? "option" : "step";
        Shape shape = shape(body);
        List<JsonNode> sets = new ArrayList<>();
        JsonNode before = null;
        JsonNode after = null;
        JsonNode pick = null;
        Doc.Map alternative = null;
        Doc.Entry blockAt = null;
        Doc.Entry withAt = null;
        Doc.Entry pathAt = null;
        JsonNode nested = null;
        JsonNode fail = null;
        JsonNode test = null;
        Doc.Entry testAt = null;
        for (var e : body.entries().entrySet()) {
            Doc value = e.getValue().value();
            String key = e.getKey();
            String moved = moved(key, value, shape, body);
            if (moved != null) {
                error(e.getValue().key(), moved);
                continue;
            }
            if (!BODY_KEYS.contains(key)) {
                error(e.getValue().key(), "unknown " + what + " key '" + key + "'");
                continue;
            }
            if (shape != null
                    && shape.block() != null
                    && shape.block().role() == BlockSpec.Role.IDENTIFIER
                    && "if".equals(key)) {
                error(e.getValue().key(), "the " + shape.block().title() + " always runs: it takes no 'if'");
                continue;
            }
            if (shape != null && !shape.takes(key)) {
                error(e.getValue().key(), shape.named() + " takes " + shape.keys() + ", not '" + key + "'");
                continue;
            }
            switch (key) {
                case "if" -> {
                    test = predicate(value);
                    testAt = e.getValue();
                }
                case "set" -> {
                    if (value instanceof Doc.Map vars) {
                        vars.entries().forEach((name, v) -> {
                            ArrayNode set =
                                    JSON.arrayNode().add("set").add(name).add(scalar(v.value()));
                            mark(set, v.key(), "set");
                            markChild(set, 1, v.key(), "variable name");
                            markChild(set, 2, v.value().source(), "value of " + name);
                            sets.add(set);
                        });
                    } else {
                        error(value.source(), "'set' must be a mapping of variable name to literal");
                    }
                }
                case "before" -> before = hookRef(value);
                case "after" -> after = hookRef(value);
                case "pick" -> pick = hookRef(value);
                case "block" -> blockAt = e.getValue();
                case "with" -> withAt = e.getValue();
                case "path" -> pathAt = e.getValue();
                case "alternative", "steps" -> {
                    if (!(value instanceof Doc.Map m) || m.entries().isEmpty()) {
                        error(value.source(), "'" + key + "' must be a non empty mapping");
                    } else if ("steps".equals(key)) {
                        List<JsonNode> children = steps(m, true);
                        if (children.size() == 1) {
                            nested = children.get(0);
                        } else if (!children.isEmpty()) {
                            ArrayNode seq = JSON.arrayNode().add("seq");
                            children.forEach(seq::add);
                            mark(seq, value.source(), "steps");
                            nested = seq;
                        }
                    } else if (m.entries().size() < 2) {
                        error(
                                value.source(),
                                "a choice needs two options or more; with one, use that block as the step");
                    } else {
                        // Lowered once the whole body is read, since 'pick' may come after it
                        alternative = m;
                    }
                }
                case "fail" -> {
                    ArrayNode node = JSON.arrayNode().add("fail").add(scalar(value));
                    mark(node, value.source(), "fail");
                    markChild(node, 1, value.source(), "fail reason");
                    fail = node;
                }
                default -> throw new IllegalStateException("BODY_KEYS has " + key);
            }
        }
        if (shape == null) {
            // A key the surface moved was reported where it is, with what to write instead
            if (body.entries().entrySet().stream()
                    .noneMatch(k -> moved(k.getKey(), k.getValue().value(), null, body) != null)) {
                error(owner, what + " needs one of 'block', 'alternative', 'steps', 'set' or 'fail'");
            }
            return null;
        }
        if (shape.kind() == Kind.BLOCK && shape.block() == null) {
            if (blockAt != null) {
                error(
                        blockAt.value().source(),
                        blockAt.value() instanceof Doc.Scalar
                                ? "unknown block " + unknownBlock(blockAt.value())
                                : "a block is named by its id"
                                        + unknownBlock(blockAt.value()).replaceFirst("^'[^']*'", ""));
            }
            return null;
        }
        place(shape, owner, where);
        if (alternative != null) nested = alternative(alternative, pick);
        List<JsonNode> parts = new ArrayList<>(sets);
        if (shape.block() != null && blockAt != null) {
            List<JsonNode> lowered = block(shape.block(), blockAt, withAt, where);
            parts.addAll(lowered);
            if (pathAt != null) path(pathAt, firstScreen(lowered.get(0)));
        }
        for (JsonNode part : new JsonNode[] {nested, fail}) {
            if (part != null) parts.add(part);
        }
        if (parts.isEmpty()) return null;
        if (before != null) parts.add(0, before);
        if (after != null) parts.add(after);
        JsonNode inner = seq(parts, owner, what);
        if (test == null || testAt == null) return new Lowered(inner, null);
        if (where == Where.OPTION) {
            ObjectNode guard = JSON.objectNode().set("guard", test);
            mark(guard, testAt.value().source(), "if");
            return new Lowered(inner, guard);
        }
        ArrayNode when = JSON.arrayNode().add("when").add(test).add(inner);
        mark(when, testAt.value().source(), "if");
        return new Lowered(when, null);
    }

    /**
     * What to write instead of a key the surface no longer has: a step names its block,
     * which brings its screen, handler and attempts; a hook runs around a block. Null for any other
     * key.
     */
    private @Nullable String moved(String key, Doc value, @Nullable Shape shape, Doc.Map body) {
        return switch (key) {
            case "use" -> {
                String id = value instanceof Doc.Scalar s && s.value() instanceof String handler
                        ? options.blocks().values().stream()
                                .filter(b -> b.handler().equals(handler))
                                .map(BlockSpec::id)
                                .findFirst()
                                .orElse(null)
                        : null;
                yield "a step names its block, which brings its handler: write 'block: " + (id == null ? "<block>" : id)
                        + "'";
            }
            case "screen" -> "a block shows its own screen; change what it offers under 'with'";
            case "retry" -> {
                // The block the step is, or the one its old 'use' names
                BlockSpec block = shape != null ? shape.block() : null;
                Doc.Entry use = body.entries().get("use");
                if (block == null && use != null && use.value() instanceof Doc.Scalar h) {
                    block = options.blocks().values().stream()
                            .filter(b -> b.handler().equals(h.value()))
                            .findFirst()
                            .orElse(null);
                }
                Object attempts = block == null ? null : attemptsOf(block);
                String title = block == null ? "" : block.title();
                yield attempts != null && value instanceof Doc.Scalar v && attempts.equals(v.value())
                        ? article(title) + " block makes " + attempts + " attempts by default: remove 'retry'"
                        : "attempts are a setting of a block: write 'with: { attempts: " + text(value) + " }'";
            }
            case "hook" ->
                "a hook runs around a block: write 'before: " + text(value)
                        + "' on the block it runs before, or 'after: " + text(value) + "' on the one it follows";
            default -> null;
        };
    }

    private String unknownBlock(Doc value) {
        String known = options.blocks().keySet().stream().sorted().collect(java.util.stream.Collectors.joining(", "));
        return "'" + text(value) + "'" + (known.isEmpty() ? "" : "; the blocks are " + known);
    }

    /** The block's default attempts, or null if it does not retry. */
    private static @Nullable Object attemptsOf(BlockSpec block) {
        return block.settings().values().stream()
                .filter(s -> s.target() == BlockSpec.Setting.Target.ATTEMPTS)
                .map(BlockSpec.Setting::byDefault)
                .findFirst()
                .orElse(null);
    }

    /** The one part, or {@code (seq parts...)}. */
    private JsonNode seq(List<JsonNode> parts, SourceRef owner, String what) {
        if (parts.size() == 1) return parts.get(0);
        ArrayNode seq = JSON.arrayNode().add("seq");
        parts.forEach(seq::add);
        mark(seq, owner, what);
        return seq;
    }

    /** The first {@code screen} node of a lowered block: under its retry, if it has one. */
    private static JsonNode firstScreen(JsonNode lowered) {
        JsonNode node = lowered;
        while (node.isArray() && !"screen".equals(node.path(0).asText())) {
            node = "retry".equals(node.path(0).asText()) ? node.path(2) : node.path(1);
        }
        return node;
    }

    /**
     * Where each block may go: the identifier first and only there, consent at the top, options of
     * one credential, and no password block when the identifier asks for the password.
     */
    private void place(Shape shape, SourceRef owner, Where where) {
        BlockSpec.Role role = shape.role();
        String title = shape.block() == null ? "" : shape.block().title();
        if (where == Where.FIRST && identifier != null && role != BlockSpec.Role.IDENTIFIER) {
            error(
                    owner,
                    "the first step is the " + identifier.title() + " ('block: " + identifier.id() + "'), not "
                            + shape.named());
        } else if (role == BlockSpec.Role.IDENTIFIER && where != Where.FIRST) {
            error(owner, "the " + title + " is the first step and the only one; remove this block");
        } else if (where == Where.OPTION && !shape.option()) {
            error(owner, "an option is one credential block or a 'fail', not " + shape.named());
        } else if (role == BlockSpec.Role.APPROVE && !approving) {
            error(owner, "the " + title + " block approves a sign in on another device; it is a step of 'approve'");
        } else if (role == BlockSpec.Role.APPROVE && where != Where.STEP) {
            error(owner, title + " is a step of its own, not inside a group or a choice");
        } else if (role == BlockSpec.Role.CONSENT && approving) {
            error(owner, title + " belongs in 'login'; 'approve' asks for no consent");
        } else if (role == BlockSpec.Role.CONSENT && where == Where.NESTED) {
            error(owner, title + " is a step of its own, not inside a group");
        } else if (role == BlockSpec.Role.PASSWORD && passwordOnIdentifier) {
            error(
                    owner,
                    "the identifier already asks for the password; remove this block, or ask for the identifier"
                            + " first");
        }
    }

    /**
     * A block's parts: its screen, with its fixed options and the ones its settings turn on, and its
     * handler's call, run as many times as its attempts allow. An identifier that asks for the
     * password also calls the password block's handler.
     */
    private List<JsonNode> block(BlockSpec block, Doc.Entry blockAt, Doc.@Nullable Entry withAt, Where where) {
        SourceRef at = blockAt.value().source();
        Map<String, Object> values = settings(block, withAt);
        ObjectNode opts = JSON.objectNode();
        block.fixed().forEach((k, v) -> opts.set(k, literal(v)));
        boolean asksPassword = block.settings().entrySet().stream()
                .anyMatch(e -> e.getValue().target() == BlockSpec.Setting.Target.PASSWORD
                        && Boolean.TRUE.equals(values.get(e.getKey())));
        // What the flow wrote itself, as against a default; asking for the password turns off the
        // options a default turned on, and refuses only those the flow turned on
        java.util.Set<String> written = withAt != null && withAt.value() instanceof Doc.Map with
                ? with.entries().keySet()
                : java.util.Set.of();
        String other = null;
        for (var s : block.settings().entrySet()) {
            Object value = values.get(s.getKey());
            switch (s.getValue().target()) {
                case OPTION -> {
                    if (asksPassword && !written.contains(s.getKey())) continue;
                    if (value != null && !Boolean.FALSE.equals(value)) {
                        opts.set(s.getKey(), literal(value));
                        if (Boolean.TRUE.equals(value)) other = s.getKey();
                    }
                }
                case PASSWORD -> {
                    if (Boolean.TRUE.equals(value)) opts.put(s.getKey(), true);
                }
                case ATTEMPTS -> {}
            }
        }
        ArrayNode screen = JSON.arrayNode().add("screen").add(block.screen());
        mark(screen, at, "screen");
        markChild(screen, 1, at, "screen type");
        if (!opts.isEmpty()) {
            mark(opts, withAt != null ? withAt.value().source() : at, "screen options");
            screen.add(opts);
        }
        List<JsonNode> parts = new ArrayList<>();
        parts.add(screen);
        parts.add(call(block.handler(), at));
        boolean retries = true;
        if (block.role() == BlockSpec.Role.IDENTIFIER) {
            if (asksPassword && other != null) {
                error(at, "the " + block.title() + " asks for the password with no other option, not '" + other + "'");
            }
            BlockSpec verifies = password;
            if (asksPassword && verifies == null) {
                error(at, "no block verifies a password, so the " + block.title() + " cannot ask for one");
            } else if (asksPassword && verifies != null) {
                if (where == Where.FIRST) passwordOnIdentifier = true;
                parts.add(call(verifies.handler(), at));
            } else {
                // An identifier alone never fails, so it has nothing to retry
                retries = false;
                if (withAt != null && withAt.value() instanceof Doc.Map with) {
                    block.settings().forEach((name, s) -> {
                        if (s.target() == BlockSpec.Setting.Target.ATTEMPTS
                                && with.entries().containsKey(name)) {
                            error(
                                    with.entries().get(name).key(),
                                    "the " + block.title() + " retries only when it asks for the password: an"
                                            + " identifier alone never fails");
                        }
                    });
                }
            }
        }
        int attempts = 1;
        SourceRef attemptsAt = at;
        for (var s : block.settings().entrySet()) {
            if (s.getValue().target() == BlockSpec.Setting.Target.ATTEMPTS
                    && values.get(s.getKey()) instanceof Integer n) {
                attempts = n;
                if (withAt != null && withAt.value() instanceof Doc.Map with) {
                    Doc.Entry e = with.entries().get(s.getKey());
                    if (e != null) attemptsAt = e.value().source();
                }
            }
        }
        if (!retries || attempts == 1) return parts;
        ArrayNode retry = JSON.arrayNode().add("retry").add(attempts).add(seq(parts, at, "block"));
        mark(retry, attemptsAt, "attempts");
        markChild(retry, 1, attemptsAt, "attempts");
        return List.of(retry);
    }

    /** The block's settings: its defaults, and what the flow sets under {@code with}, checked against them. */
    private Map<String, Object> settings(BlockSpec block, Doc.@Nullable Entry withAt) {
        Map<String, Object> values = new LinkedHashMap<>();
        block.settings().forEach((name, s) -> values.put(name, s.byDefault()));
        if (withAt == null) return values;
        if (!(withAt.value() instanceof Doc.Map with)) {
            error(withAt.value().source(), "'with' must be a mapping of setting to value");
            return values;
        }
        String known = block.settings().keySet().stream()
                .sorted()
                .map(k -> "'" + k + "'")
                .collect(java.util.stream.Collectors.joining(", "));
        with.entries().forEach((name, e) -> {
            BlockSpec.Setting s = block.settings().get(name);
            Object value = e.value() instanceof Doc.Scalar sc ? sc.value() : null;
            if (s == null) {
                error(e.key(), article(block.title()) + " block has no setting '" + name + "'; it has " + known);
            } else if (s.byDefault() instanceof Boolean && !(value instanceof Boolean)) {
                error(e.value().source(), "'" + name + "' is true or false");
            } else if (s.byDefault() instanceof Integer && !(value instanceof Integer n && n >= 1)) {
                error(e.value().source(), "'" + name + "' is a whole number of 1 or more");
            } else {
                values.put(name, value);
            }
        });
        return values;
    }

    private ArrayNode call(String handler, SourceRef at) {
        ArrayNode node = JSON.arrayNode().add("call").add(handler);
        mark(node, at, "block");
        markChild(node, 1, at, "handler name");
        return node;
    }

    private static JsonNode literal(Object value) {
        return switch (value) {
            case Boolean b -> JSON.booleanNode(b);
            case Integer i -> JSON.numberNode(i);
            default -> JSON.textNode(String.valueOf(value));
        };
    }

    /** The caller's block with this role, if there is one. */
    private static @Nullable BlockSpec blockOf(Options options, BlockSpec.Role role) {
        return options.blocks().values().stream()
                .filter(b -> b.role() == role)
                .sorted(java.util.Comparator.comparing(BlockSpec::id))
                .findFirst()
                .orElse(null);
    }

    private @Nullable JsonNode alternative(Doc.Map options, @Nullable JsonNode pick) {
        ArrayNode list = JSON.arrayNode();
        options.entries().forEach((id, option) -> {
            if (!(option.value() instanceof Doc.Map body)) {
                error(option.value().source(), "option must be a mapping");
                return;
            }
            Lowered inner = body(body, option.key(), Where.OPTION);
            if (inner == null) return;
            ArrayNode opt = JSON.arrayNode().add("opt").add(id).add(inner.node());
            if (inner.guard() != null) opt.add(inner.guard());
            mark(opt, option.key(), "option");
            markChild(opt, 1, option.key(), "option id");
            list.add(opt);
        });
        if (list.isEmpty()) return null;
        ArrayNode choose = JSON.arrayNode().add("choose").add(list);
        if (pick != null) choose.add(JSON.objectNode().set("pick", pick));
        mark(choose, options.source(), "alternative");
        return choose;
    }

    /** Whether a named block is a consent one. */
    private boolean consent(@Nullable BlockSpec block) {
        return block != null
                && (options.consentHandlers().contains(block.handler())
                        || options.consentScreens().contains(block.screen()));
    }

    /** A scalar's text for a diagnostic. */
    private static String text(Doc value) {
        return value instanceof Doc.Scalar s && s.value() != null ? String.valueOf(s.value()) : "...";
    }

    /** A body's {@code path}, which is the option of the screen the same body shows. */
    private void path(Doc.Entry at, @Nullable JsonNode screen) {
        if (!(screen instanceof ArrayNode node)) {
            error(at.key(), "'path' needs a 'screen' in the same body");
            return;
        }
        if (node.size() < 3) {
            ObjectNode opts = JSON.objectNode();
            mark(opts, at.value().source(), "screen options");
            node.add(opts);
        }
        ObjectNode opts = (ObjectNode) node.get(2);
        if (opts.has("path")) {
            error(at.key(), "the screen already sets a 'path'");
            return;
        }
        opts.set("path", scalar(at.value()));
        // What is wrong with a screen's path is reported where the flow wrote it
        mark(node, at.value().source(), "screen");
    }

    /**
     * An {@code if} as an IR predicate. Paths in an aliased namespace are rewritten to the namespace
     * it stands for; the grammar checks the rest.
     */
    private JsonNode predicate(Doc value) {
        JsonNode json = json(value);
        JsonNode out = aliased(json);
        if (out.isContainerNode()) mark(out, value.source(), "if");
        return out;
    }

    /** Rewrites the paths of a predicate (a bare path, an operand of exists, the left of a comparison). */
    private JsonNode aliased(JsonNode p) {
        if (p.isTextual()) {
            String path = p.textValue();
            int dot = path.indexOf('.');
            String namespace = dot < 0 ? null : aliases.get(path.substring(0, dot));
            return namespace == null ? p : JSON.textNode(namespace + path.substring(dot));
        }
        if (!(p instanceof ObjectNode o) || o.size() != 1) return p;
        var e = o.properties().iterator().next();
        JsonNode arg = e.getValue();
        JsonNode rewritten = switch (e.getKey()) {
            case "and", "or" -> {
                ArrayNode all = JSON.arrayNode();
                arg.forEach(q -> all.add(aliased(q)));
                yield arg.isArray() ? all : arg;
            }
            case "not", "exists" -> aliased(arg);
            default -> {
                if (!arg.isArray() || arg.isEmpty()) yield arg;
                ArrayNode copy = ((ArrayNode) arg).deepCopy();
                copy.set(0, aliased(arg.get(0)));
                yield copy;
            }
        };
        return JSON.objectNode().set(e.getKey(), rewritten);
    }

    /** The node for a reference to a declared hook, at the position of the reference. */
    private @Nullable JsonNode hookRef(Doc value) {
        if (!(value instanceof Doc.Scalar s && s.value() instanceof String id)) {
            error(value.source(), "a hook is named by its id under 'hooks'");
            return null;
        }
        Declared hook = declared.get(id);
        if (hook == null) {
            if (!broken.contains(id)) error(value.source(), "unknown hook '" + id + "'; declare it under 'hooks'");
            return null;
        }
        ArrayNode node = JSON.arrayNode().add("hook").add(id).add(hook.hash());
        mark(node, value.source(), "hook");
        markChild(node, 1, hook.source(), "hook id");
        return node;
    }

    // ---- hooks ----

    private void hooks(Doc section) {
        if (!(section instanceof Doc.Map m)) {
            error(section.source(), "'hooks' must be a mapping of hook id to hook");
            return;
        }
        m.entries().forEach((id, e) -> {
            if (e.value() instanceof Doc.Map body) {
                HookDef def = hook(body);
                if (def != null) {
                    declared.put(id, new Declared(def.hash(), e.key()));
                    hooks.put(def.hash(), def);
                } else {
                    broken.add(id);
                }
            } else {
                error(e.value().source(), "hook must be a mapping");
            }
        });
    }

    private @Nullable HookDef hook(Doc.Map body) {
        String source = null;
        List<String> http = new ArrayList<>();
        int timeout = HookDef.DEFAULT_TIMEOUT_MS;
        for (var e : body.entries().entrySet()) {
            Doc value = e.getValue().value();
            switch (e.getKey()) {
                case "file" -> {
                    if (value instanceof Doc.Scalar s
                            && s.value() instanceof String name
                            && FILE.matcher(name).matches()) {
                        Optional<String> text = files.apply(name);
                        if (text.isPresent()) {
                            source = text.get();
                        } else {
                            error(value.source(), "hook file '" + name + "' not found");
                        }
                    } else {
                        error(value.source(), "hook file must match " + FILE);
                    }
                }
                case "source" -> {
                    if (value instanceof Doc.Scalar s && s.value() instanceof String text) {
                        source = text;
                    } else {
                        error(value.source(), "'source' must be a string of JavaScript");
                    }
                }
                case "capabilities" -> {
                    if (!(value instanceof Doc.Map caps)) {
                        error(value.source(), "'capabilities' must be a mapping");
                        continue;
                    }
                    caps.entries().forEach((cap, c) -> {
                        if (!cap.equals("http")) {
                            error(c.key(), "unknown capability '" + cap + "'; hooks may have 'http'");
                        } else if (c.value() instanceof Doc.Seq targets) {
                            targets.items().forEach(t -> {
                                if (t instanceof Doc.Scalar s
                                        && s.value() instanceof String target
                                        && HookDef.TARGET.matcher(target).matches()) {
                                    http.add(target);
                                } else {
                                    error(t.source(), "egress target must match " + HookDef.TARGET);
                                }
                            });
                        } else {
                            error(c.value().source(), "'http' must be a list of egress target names");
                        }
                    });
                }
                case "timeout_ms" -> {
                    if (value instanceof Doc.Scalar s
                            && s.value() instanceof Integer ms
                            && ms >= 1
                            && ms <= HookDef.MAX_TIMEOUT_MS) {
                        timeout = ms;
                    } else {
                        error(value.source(), "timeout_ms must be an integer from 1 to " + HookDef.MAX_TIMEOUT_MS);
                    }
                }
                default -> error(e.getValue().key(), "unknown hook key '" + e.getKey() + "'");
            }
        }
        if (source == null) {
            if (!body.entries().containsKey("file") && !body.entries().containsKey("source")) {
                error(body.source(), "hook needs 'file' or 'source'");
            }
            return null;
        }
        try {
            return new HookDef(source, http, timeout);
        } catch (IllegalArgumentException e) {
            error(body.source(), String.valueOf(e.getMessage()));
            return null;
        }
    }

    // ---- JSON ----

    /** A scalar as JSON, typed as the document typed it; the grammar decides whether it fits. */
    private JsonNode scalar(Doc doc) {
        if (doc instanceof Doc.Scalar) return json(doc);
        error(doc.source(), "expected a single value");
        return JSON.nullNode();
    }

    private static JsonNode json(Doc doc) {
        return switch (doc) {
            case Doc.Scalar s ->
                switch (s.value()) {
                    case null -> JSON.nullNode();
                    case Boolean b -> JSON.booleanNode(b);
                    case Integer i -> JSON.numberNode(i);
                    case Long l -> JSON.numberNode(l);
                    case Double d -> JSON.numberNode(d);
                    default -> JSON.textNode(String.valueOf(s.value()));
                };
            case Doc.Seq q -> {
                ArrayNode out = JSON.arrayNode();
                q.items().forEach(i -> out.add(json(i)));
                yield out;
            }
            case Doc.Map m -> {
                ObjectNode out = JSON.objectNode();
                m.entries().forEach((k, e) -> out.set(k, json(e.value())));
                yield out;
            }
        };
    }

    // ---- source map ----

    private void mark(JsonNode node, SourceRef source, String what) {
        marks.put(node, new Mark(source, what));
    }

    private void markChild(JsonNode container, int index, SourceRef source, String what) {
        childMarks.computeIfAbsent(container, c -> new HashMap<>()).put(index, new Mark(source, what));
    }

    /** Every marked node of the final JSON by its JSON pointer. */
    private Map<String, Mark> pointers(JsonNode root) {
        Map<String, Mark> out = new HashMap<>();
        walk(root, "", out);
        return out;
    }

    private void walk(JsonNode node, String at, Map<String, Mark> out) {
        Mark mark = marks.get(node);
        if (mark != null) out.put(at, mark);
        Map<Integer, Mark> children = childMarks.getOrDefault(node, Map.of());
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                if (children.containsKey(i)) out.put(at + "/" + i, children.get(i));
                walk(node.get(i), at + "/" + i, out);
            }
        } else if (node.isObject()) {
            node.properties().forEach(e -> walk(e.getValue(), at + "/" + e.getKey(), out));
        }
    }

    /** The mark of the node at {@code at}, else of its nearest marked ancestor. */
    private Mark mark(Map<String, Mark> table, String at) {
        for (String p = at; ; p = p.substring(0, p.lastIndexOf('/'))) {
            Mark m = table.get(p);
            if (m != null) return m;
            if (p.isEmpty()) return new Mark(new SourceRef(origin, 1, 1), "flow");
        }
    }

    private Diagnostic diagnostic(Map<String, Mark> table, IrException e) {
        Mark m = mark(table, e.at());
        return new Diagnostic(m.source(), m.what() + ": " + e.reason());
    }

    private List<Diagnostic> sorted(List<Diagnostic> all) {
        return all.stream()
                .sorted(Comparator.comparingInt((Diagnostic d) -> d.source().line())
                        .thenComparingInt(d -> d.source().column()))
                .toList();
    }

    private void error(SourceRef at, String message) {
        errors.add(new Diagnostic(at, message));
    }
}
