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
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Lowers the YAML surface syntax to Core IR. {@link YamlReader} reads the YAML into a positioned
 * {@link Doc}; lowering turns that into IR JSON, remembering which part of the document each JSON
 * node came from; {@link IrCodec} then checks the JSON against the one grammar ({@code
 * schema/ir.schema.json}) and every violation is reported at the YAML that produced it. The
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
 *     step-id:
 *       if: predicate                      # (when predicate ...); on an option, its guard
 *       retry: 3                           # (retry 3 ...)
 *       set: { name: literal }             # (set name literal), first
 *       hook: hook-id                      # (hook hook-id hash)
 *       screen: type | { type: t, opt: literal }
 *       use: handler
 *       alternative: { option-id: body }   # (choose ((opt option-id body) ...)); or
 *       pick: hook-id                      # with alternative: (choose (...) {:pick (hook ...)})
 *       steps: { step-id: body }           # nested steps
 *       fail: reason                       # last
 * </pre>
 *
 * A body needs at least one of set, hook, screen, use, alternative, steps, fail; its parts run in
 * the order above. Option bodies take the same keys. Predicates have the IR shape ({@code eq},
 * {@code and}, ...). A caller may give namespace aliases, which predicates' paths are rewritten
 * from (the app passes its host's legacy spelling); the compiler itself knows none. Each entry compiles to
 * {@code (seq (step ...) ... (succeed))}. A hook is pinned by the hash of its definition ({@link
 * HookDef}). docs/roadmap/dsl-and-ir.md has the lowering table.
 *
 * <p>Consent is a step like any other (ADR 0026): the steps of {@code login} up to the first one
 * that shows the consent screen or calls {@value #CONSENT_HANDLER} lower to the {@code authenticate}
 * entry, and that step and
 * every step after it to the {@code consent} entry, since they run on the host's consent challenge,
 * after the sign in was accepted. The IR keeps the two entries.
 */
public final class FlowCompiler {

    /** A hook file name: surface only, it never reaches the IR. */
    private static final Pattern FILE = Pattern.compile("[a-z0-9][a-z0-9_-]*\\.js");

    /** The YAML key of the steps, from the first one to consent. */
    private static final String LOGIN = "login";

    /** The id of the entry the consent steps lower to. */
    private static final String CONSENT = "consent";

    /** The handler whose step starts the consent steps. */
    static final String CONSENT_HANDLER = "consent.record";

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
    private final Map<JsonNode, Mark> marks = new IdentityHashMap<>();

    private final Map<JsonNode, Map<Integer, Mark>> childMarks = new IdentityHashMap<>();

    /** Namespace aliases: a predicate path {@code <alias>.rest} lowers to {@code <namespace>.rest}. */
    private final Map<String, String> aliases;

    private FlowCompiler(String origin, Function<String, Optional<String>> files, Map<String, String> aliases) {
        this.origin = origin;
        this.files = files;
        this.aliases = Map.copyOf(aliases);
    }

    /** @param origin file name used in source refs and diagnostics */
    public static Flow compile(String origin, String yaml) {
        return compileWithHooks(origin, yaml, name -> Optional.empty()).flow();
    }

    /**
     * @param files reads a hook's {@code file:} next to the flow; empty if there is no such file
     */
    public static Compiled compileWithHooks(String origin, String yaml, Function<String, Optional<String>> files) {
        return compileWithHooks(origin, yaml, files, Map.of());
    }

    /**
     * @param files reads a hook's {@code file:} next to the flow; empty if there is no such file
     * @param aliases predicate path namespaces the YAML may use for an IR namespace, e.g. {@code
     *     legacy} for {@code host}
     */
    public static Compiled compileWithHooks(
            String origin, String yaml, Function<String, Optional<String>> files, Map<String, String> aliases) {
        FlowCompiler compiler = new FlowCompiler(origin, files, aliases);
        Flow flow = compiler.compile(yaml);
        return new Compiled(flow, compiler.hooks);
    }

    /** The IR JSON the YAML lowers to, before any check; for golden tests and tools. */
    static JsonNode lower(String origin, String yaml) {
        FlowCompiler compiler = new FlowCompiler(origin, name -> Optional.empty(), Map.of());
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
                case CONSENT ->
                    error(e.key(), "consent is a step of 'login' now: move these steps to the end of 'login.steps'");
                default -> error(e.key(), "unknown key '" + key + "'");
            }
        });
        if (!top.entries().containsKey(LOGIN)) {
            error(top.source(), "missing 'login'");
        }
        return flow;
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
    private static boolean recordsConsent(Doc doc) {
        if (!(doc instanceof Doc.Map m)) return false;
        for (var e : m.entries().entrySet()) {
            Doc value = e.getValue().value();
            boolean asks = switch (e.getKey()) {
                case "use" -> value instanceof Doc.Scalar s && CONSENT_HANDLER.equals(s.value());
                case "screen" ->
                    (value instanceof Doc.Scalar s && CONSENT.equals(s.value()))
                            || (value instanceof Doc.Map screen
                                    && screen.entries().get("type") instanceof Doc.Entry type
                                    && type.value() instanceof Doc.Scalar t
                                    && CONSENT.equals(t.value()));
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
        steps(new Doc.Map(steps, at)).forEach(seq::add);
        ArrayNode succeed = JSON.arrayNode().add("succeed");
        mark(succeed, at, "succeed");
        entry.add(seq.add(succeed));
        mark(entry, at, "'" + id + "'");
        return entry;
    }

    private List<JsonNode> steps(Doc.Map steps) {
        List<JsonNode> out = new ArrayList<>();
        steps.entries().forEach((id, step) -> {
            if (!(step.value() instanceof Doc.Map body)) {
                error(step.value().source(), "step must be a mapping");
                return;
            }
            Lowered inner = body(body, step.key(), "step", false);
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

    /**
     * A step or option body: its parts in a fixed order, wrapped in retry, then in when (a step) or
     * guarded (an option).
     */
    private @Nullable Lowered body(Doc.Map body, SourceRef owner, String what, boolean option) {
        List<JsonNode> sets = new ArrayList<>();
        JsonNode hook = null;
        JsonNode pick = null;
        Doc.Entry pickAt = null;
        Doc.Map alternative = null;
        JsonNode screen = null;
        JsonNode call = null;
        JsonNode nested = null;
        JsonNode fail = null;
        JsonNode test = null;
        Doc.Entry testAt = null;
        Doc.Entry retryAt = null;
        for (var e : body.entries().entrySet()) {
            Doc value = e.getValue().value();
            switch (e.getKey()) {
                case "if" -> {
                    test = predicate(value);
                    testAt = e.getValue();
                }
                case "retry" -> retryAt = e.getValue();
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
                case "hook" -> hook = hookRef(value);
                case "pick" -> {
                    pick = hookRef(value);
                    pickAt = e.getValue();
                }
                case "screen" -> screen = screen(value);
                case "use" -> {
                    ArrayNode node = JSON.arrayNode().add("call").add(scalar(value));
                    mark(node, value.source(), "use");
                    markChild(node, 1, value.source(), "handler name");
                    call = node;
                }
                case "alternative", "steps" -> {
                    if (nested != null || alternative != null) {
                        error(e.getValue().key(), "'alternative' and 'steps' cannot be combined");
                    } else if (!(value instanceof Doc.Map m) || m.entries().isEmpty()) {
                        error(value.source(), "'" + e.getKey() + "' must be a non empty mapping");
                    } else if (e.getKey().equals("steps")) {
                        List<JsonNode> children = steps(m);
                        if (children.size() == 1) {
                            nested = children.get(0);
                        } else if (!children.isEmpty()) {
                            ArrayNode seq = JSON.arrayNode().add("seq");
                            children.forEach(seq::add);
                            mark(seq, value.source(), "steps");
                            nested = seq;
                        }
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
                default -> error(e.getValue().key(), "unknown " + what + " key '" + e.getKey() + "'");
            }
        }
        if (pickAt != null && alternative == null) {
            error(pickAt.value().source(), "'pick' needs an 'alternative' to pick from");
        }
        if (alternative != null) nested = alternative(alternative, pick);
        List<JsonNode> parts = new ArrayList<>(sets);
        for (JsonNode part : new JsonNode[] {hook, screen, call, nested, fail}) {
            if (part != null) parts.add(part);
        }
        if (parts.isEmpty()) {
            // An unknown hook was reported where it is named
            if (!body.entries().containsKey("hook")) {
                error(owner, what + " needs one of 'set', 'hook', 'screen', 'use', 'alternative', 'steps' or 'fail'");
            }
            return null;
        }
        JsonNode inner = parts.get(0);
        if (parts.size() > 1) {
            ArrayNode seq = JSON.arrayNode().add("seq");
            parts.forEach(seq::add);
            mark(seq, owner, what);
            inner = seq;
        }
        if (retryAt != null) {
            ArrayNode retry =
                    JSON.arrayNode().add("retry").add(scalar(retryAt.value())).add(inner);
            mark(retry, retryAt.value().source(), "retry");
            markChild(retry, 1, retryAt.value().source(), "retry");
            inner = retry;
        }
        if (test == null || testAt == null) return new Lowered(inner, null);
        if (option) {
            ObjectNode guard = JSON.objectNode().set("guard", test);
            mark(guard, testAt.value().source(), "if");
            return new Lowered(inner, guard);
        }
        ArrayNode when = JSON.arrayNode().add("when").add(test).add(inner);
        mark(when, testAt.value().source(), "if");
        return new Lowered(when, null);
    }

    private @Nullable JsonNode alternative(Doc.Map options, @Nullable JsonNode pick) {
        ArrayNode list = JSON.arrayNode();
        options.entries().forEach((id, option) -> {
            if (!(option.value() instanceof Doc.Map body)) {
                error(option.value().source(), "option must be a mapping");
                return;
            }
            Lowered inner = body(body, option.key(), "option", true);
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

    /** {@code type} or {@code { type: t, opt: literal, ... }}. */
    private JsonNode screen(Doc value) {
        ArrayNode node = JSON.arrayNode().add("screen");
        mark(node, value.source(), "screen");
        if (!(value instanceof Doc.Map m)) {
            markChild(node, 1, value.source(), "screen type");
            return node.add(scalar(value));
        }
        Doc.Entry type = m.entries().get("type");
        if (type == null) {
            error(value.source(), "screen needs a 'type'");
            return node.add("invalid");
        }
        node.add(scalar(type.value()));
        markChild(node, 1, type.value().source(), "screen type");
        ObjectNode opts = JSON.objectNode();
        m.entries().forEach((k, v) -> {
            if (!k.equals("type")) opts.set(k, scalar(v.value()));
        });
        if (!opts.isEmpty()) {
            mark(opts, value.source(), "screen options");
            node.add(opts);
        }
        return node;
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
