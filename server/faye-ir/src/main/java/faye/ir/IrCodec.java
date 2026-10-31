package faye.ir;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Reads and writes the JSON form of the IR defined by {@code schema/ir.schema.json}, computes the
 * canonical bytes and content hash of a flow, and prints the display notation. Source refs are not
 * part of the JSON form.
 */
public final class IrCodec {

    /** The IR format this codec writes. */
    public static final int FORMAT = 1;

    /**
     * The IR formats this codec reads. An older format is upgraded on read to {@link #FORMAT}, keeping
     * its node paths, and stays readable while any stored version may be in it: a tenant's current
     * version has no TTL (docs/roadmap/ir-spec.md, Versioning).
     */
    public static final Set<Integer> FORMATS = Set.of(1);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    /** The grammar of the read side, shipped on the classpath from the repo's schema/ directory. */
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(
            SpecVersion.VersionFlag.V202012,
            b -> b.schemaMappers(m -> m.mapPrefix("https://faye.invalid/schema/", "classpath:")));

    private static final JsonSchema SCHEMA =
            FACTORY.getSchema(SchemaLocation.of("https://faye.invalid/schema/ir.schema.json"));
    /** The schema's definitions by name, compiled when first asked for. */
    private static final Map<String, JsonSchema> DEFS = new java.util.concurrent.ConcurrentHashMap<>();

    private IrCodec() {}

    // ---- write ----

    public static ArrayNode toJson(Flow flow) {
        ArrayNode out = JSON.arrayNode().add("flow").add(FORMAT).add(flow.id()).add(flow.version());
        flow.entries()
                .forEach((id, entry) -> out.add(JSON.arrayNode()
                        .add("entry")
                        .add(id)
                        .add(entry.kind().wireName())
                        .add(toJson(entry.body()))));
        return out;
    }

    public static ArrayNode toJson(Node node) {
        ArrayNode out = JSON.arrayNode();
        switch (node) {
            case Seq s -> {
                out.add("seq");
                s.children().forEach(c -> out.add(toJson(c)));
            }
            case Step s -> out.add("step").add(s.id()).add(toJson(s.body()));
            case Screen s -> {
                out.add("screen").add(s.type());
                if (!s.opts().isEmpty()) out.add(literals(s.opts()));
            }
            case Call c -> {
                out.add("call").add(c.handler());
                if (!c.args().isEmpty()) out.add(literals(c.args()));
            }
            case Hook h -> out.add("hook").add(h.id()).add(h.hash());
            case Choose c -> {
                ArrayNode options = JSON.arrayNode();
                c.options().forEach(o -> {
                    ArrayNode opt = JSON.arrayNode().add("opt").add(o.id()).add(toJson(o.body()));
                    if (o.guard() != null) opt.add(op("guard", toJson(o.guard())));
                    options.add(opt);
                });
                out.add("choose").add(options);
                if (c.pick() != null) out.add(op("pick", toJson(c.pick())));
            }
            case When w -> out.add("when").add(toJson(w.test())).add(toJson(w.body()));
            case Retry r -> out.add("retry").add(r.attempts()).add(toJson(r.body()));
            case SetVar s -> out.add("set").add(s.name()).add(literal(s.value()));
            case Succeed s -> out.add("succeed");
            case Fail f -> out.add("fail").add(f.reason());
        }
        return out;
    }

    public static JsonNode toJson(Predicate predicate) {
        return switch (predicate) {
            case Predicate.Path p -> JSON.textNode(p.path());
            case Predicate.And a -> op("and", list(a.all()));
            case Predicate.Or o -> op("or", list(o.any()));
            case Predicate.Not n -> op("not", toJson(n.operand()));
            case Predicate.Exists e -> op("exists", JSON.textNode(e.path()));
            case Predicate.Compare c ->
                op(c.op().wireName(), JSON.arrayNode().add(c.path()).add(literal(c.value())));
        };
    }

    private static ObjectNode op(String name, JsonNode operand) {
        ObjectNode out = JSON.objectNode();
        out.set(name, operand);
        return out;
    }

    private static ArrayNode list(List<Predicate> predicates) {
        ArrayNode out = JSON.arrayNode();
        predicates.forEach(p -> out.add(toJson(p)));
        return out;
    }

    /** Compact JSON with sorted keys and empty literal objects omitted. */
    public static String canonicalJson(Flow flow) {
        try {
            return MAPPER.writeValueAsString(toJson(flow));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * {@code sha256:<hex>} of the canonical JSON. Every hook node carries its hook's hash, so the
     * hooks a version runs are part of its hash.
     */
    public static String hash(Flow flow) {
        return hash(canonicalJson(flow));
    }

    /**
     * {@code sha256:<hex>} of stored canonical JSON. A store checks a version against the bytes it
     * kept, not against a re-serialization, so a version read through an upgrade from an older format
     * keeps its address.
     */
    public static String hash(String canonicalJson) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ObjectNode literals(Map<String, Object> values) {
        ObjectNode out = JSON.objectNode();
        new TreeMap<>(values).forEach((k, v) -> out.set(k, literal(v)));
        return out;
    }

    private static JsonNode literal(Object value) {
        return switch (value) {
            case String s -> JSON.textNode(s);
            case Integer i -> JSON.numberNode(i);
            case Long l -> JSON.numberNode(l);
            case Boolean b -> JSON.booleanNode(b);
            default -> throw new IllegalArgumentException("unsupported literal: " + value);
        };
    }

    // ---- read ----

    public static Flow readFlow(String json) {
        try {
            return readFlow(MAPPER.readTree(json));
        } catch (JsonProcessingException e) {
            throw new IrException("", "not JSON: " + e.getOriginalMessage());
        }
    }

    /**
     * Validates against {@code schema/ir.schema.json}, then maps the JSON to records. On a schema
     * violation the exception carries the deepest reported instance location as its pointer.
     */
    public static Flow readFlow(JsonNode json) {
        return readFlow(json, at -> SourceRef.NONE);
    }

    /**
     * As {@link #readFlow(JsonNode)}, giving each node the source a frontend recorded for its JSON
     * pointer; the DSL compiler passes the positions of the YAML each node was lowered from.
     */
    public static Flow readFlow(JsonNode json, Function<String, SourceRef> sources) {
        List<IrException> errors = errors(json);
        if (!errors.isEmpty()) {
            throw errors.stream()
                    .max(Comparator.comparingInt(e -> e.at().split("/").length))
                    .orElseThrow();
        }
        return new Reader(sources).flow(json);
    }

    /**
     * Every reason {@code json} is not a flow this reader supports: an unsupported format alone, or
     * the schema's violations at the innermost locations reported. An error that encloses another is
     * left out, so fixing the reported ones may reveal it; the document is refused either way. Empty
     * for a valid flow, apart from the duplicate ids {@link #readFlow} refuses while mapping.
     */
    public static List<IrException> errors(JsonNode json) {
        // A format this reader does not know is refused as such, not as a grammar violation
        JsonNode format = json.path(1);
        if ("flow".equals(json.path(0).textValue())
                && format.isIntegralNumber()
                && !(format.canConvertToInt() && FORMATS.contains(format.intValue()))) {
            return List.of(
                    new IrException("/1", "unsupported IR format " + format + "; this reader supports " + FORMATS));
        }
        return leaves(SCHEMA.validate(json), "");
    }

    /** A flow mapped to records, with sources looked up by pointer. */
    private record Reader(Function<String, SourceRef> sources) {

        Flow flow(JsonNode json) {
            Map<String, Entry> entries = new LinkedHashMap<>();
            for (int i = 4; i < json.size(); i++) {
                JsonNode e = json.get(i);
                String at = "/" + i;
                String id = e.get(1).textValue();
                Entry.Kind kind = Entry.Kind.of(e.get(2).textValue()).orElseThrow();
                // What the schema cannot express: entry ids are unique
                if (entries.put(id, new Entry(kind, node(e.get(3), at + "/3"))) != null) {
                    throw new IrException(at + "/1", "duplicate entry " + id);
                }
            }
            return new Flow(json.get(2).textValue(), json.get(3).intValue(), entries);
        }

        /** Maps a validated form to its record. The schema has already checked shape and arity. */
        Node node(JsonNode json, String at) {
            SourceRef source = sources.apply(at);
            String form = json.get(0).textValue();
            int n = json.size();
            return switch (form) {
                case "seq" -> {
                    List<Node> children = new ArrayList<>();
                    for (int i = 1; i < n; i++) children.add(node(json.get(i), at + "/" + i));
                    yield new Seq(children, source);
                }
                case "step" -> new Step(json.get(1).textValue(), node(json.get(2), at + "/2"), source);
                case "screen" -> new Screen(json.get(1).textValue(), IrCodec.readLiterals(json, 2, at), source);
                case "call" -> new Call(json.get(1).textValue(), IrCodec.readLiterals(json, 2, at), source);
                case "hook" -> new Hook(json.get(1).textValue(), json.get(2).textValue(), source);
                case "choose" -> {
                    List<Choose.Opt> options = new ArrayList<>();
                    JsonNode list = json.get(1);
                    for (int i = 0; i < list.size(); i++) {
                        JsonNode o = list.get(i);
                        String oat = at + "/1/" + i;
                        String id = o.get(1).textValue();
                        // What the schema cannot express: option ids are unique
                        if (options.stream().anyMatch(x -> x.id().equals(id))) {
                            throw new IrException(oat + "/1", "duplicate option " + id);
                        }
                        Predicate guard =
                                o.has(3) ? IrCodec.mapPredicate(o.get(3).get("guard")) : null;
                        options.add(new Choose.Opt(
                                id, node(o.get(2), oat + "/2"), guard, sources.apply(oat), sources.apply(oat + "/3")));
                    }
                    Hook pick = n > 2 ? (Hook) node(json.get(2).get("pick"), at + "/2/pick") : null;
                    yield new Choose(options, pick, source);
                }
                case "when" -> new When(IrCodec.mapPredicate(json.get(1)), node(json.get(2), at + "/2"), source);
                case "retry" -> new Retry(json.get(1).intValue(), node(json.get(2), at + "/2"), source);
                case "set" -> new SetVar(json.get(1).textValue(), IrCodec.readLiteral(json.get(2), at + "/2"), source);
                case "succeed" -> new Succeed(source);
                case "fail" -> new Fail(json.get(1).textValue(), source);
                default -> throw new IrException(at + "/0", "unknown form " + form);
            };
        }
    }

    /**
     * The schema's errors at the innermost locations it reported: an error at a location that has
     * another error inside it says less than that one (the if/then dispatch reports both).
     */
    private static List<IrException> leaves(Set<ValidationMessage> messages, String at) {
        List<IrException> all = messages.stream()
                .map(m -> new IrException(at + pointer(m), m.getError()))
                .sorted(Comparator.comparing(IrException::at))
                .toList();
        List<IrException> out = new ArrayList<>();
        for (IrException e : all) {
            boolean outer = all.stream().anyMatch(o -> o.at().startsWith(e.at() + "/"));
            if (!outer
                    && out.stream()
                            .noneMatch(o -> o.at().equals(e.at()) && o.reason().equals(e.reason()))) {
                out.add(e);
            }
        }
        return out;
    }

    /** The error's instance location as a JSON pointer (the library reports JSONPath form). */
    private static String pointer(ValidationMessage error) {
        var location = error.getInstanceLocation();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < location.getNameCount(); i++) {
            out.append('/')
                    .append(String.valueOf(location.getElement(i))
                            .replace("~", "~0")
                            .replace("/", "~1"));
        }
        return out.toString();
    }

    /**
     * Whether {@code value} is valid as the schema's definition {@code def} ({@code id}, {@code key},
     * ...), for code outside the IR that must accept exactly what the IR does: a hook's var names
     * and failure reasons.
     */
    public static boolean conforms(String def, String value) {
        JsonSchema schema = DEFS.computeIfAbsent(
                def,
                d -> FACTORY.getSchema(SchemaLocation.of("https://faye.invalid/schema/ir.schema.json#/$defs/" + d)));
        return schema.validate(JSON.textNode(value)).isEmpty();
    }

    /** Maps a validated predicate to its record; the schema has already checked shape and arity. */
    private static Predicate mapPredicate(JsonNode json) {
        if (json.isTextual()) return new Predicate.Path(json.textValue());
        var e = json.properties().iterator().next();
        String op = e.getKey();
        JsonNode arg = e.getValue();
        return switch (op) {
            case "and", "or" -> {
                List<Predicate> parts = new ArrayList<>();
                for (int i = 0; i < arg.size(); i++) parts.add(mapPredicate(arg.get(i)));
                yield "and".equals(op) ? new Predicate.And(parts) : new Predicate.Or(parts);
            }
            case "not" -> new Predicate.Not(mapPredicate(arg));
            case "exists" -> new Predicate.Exists(arg.textValue());
            case "eq", "ne", "gt", "lt", "has" ->
                new Predicate.Compare(
                        Predicate.Op.valueOf(op.toUpperCase(java.util.Locale.ROOT)),
                        arg.get(0).textValue(),
                        readLiteral(arg.get(1), ""));
            default -> throw new IllegalArgumentException("unknown operator " + op);
        };
    }

    private static Map<String, Object> readLiterals(JsonNode json, int index, String at) {
        if (json.size() <= index) return Map.of();
        JsonNode obj = json.get(index);
        String here = at + "/" + index;
        if (!obj.isObject()) throw new IrException(here, "expected an object");
        Map<String, Object> out = new LinkedHashMap<>();
        for (var it = obj.properties().iterator(); it.hasNext(); ) {
            var e = it.next();
            Object value = readLiteral(e.getValue(), here + "/" + e.getKey());
            out.put(e.getKey(), value);
        }
        return out;
    }

    private static Object readLiteral(JsonNode v, String at) {
        if (v.isTextual()) return v.textValue();
        if (v.isBoolean()) return v.booleanValue();
        if (v.isIntegralNumber() && v.canConvertToLong()) {
            return v.canConvertToInt() ? (Object) v.intValue() : v.longValue();
        }
        throw new IrException(at, "expected a string, integer or boolean");
    }

    // ---- display ----

    /** Strings that display bare: forms, ids, names, paths, kinds and hashes. */
    private static final java.util.regex.Pattern BARE = java.util.regex.Pattern.compile("[a-z][a-z0-9_.:-]*");

    private static final Set<String> KEYWORDS = Set.of("true", "false", "null");

    private static final int WIDTH = 100;

    /**
     * The flow in the S expression notation the docs use: an array is a list, an object is {@code
     * {:key value ...}}, and a string is bare when it reads as a name, else quoted. It is a view of the
     * canonical JSON, position for position, so it carries no grammar of its own. Nothing parses it.
     */
    public static String display(Flow flow) {
        StringBuilder out = new StringBuilder();
        display(toJson(flow), 0, out);
        return out.toString();
    }

    private static void display(JsonNode json, int indent, StringBuilder out) {
        String inline = inline(json);
        if (indent + inline.length() <= WIDTH || !(json.isArray() || json.isObject())) {
            out.append(inline);
            return;
        }
        if (json.isObject()) {
            out.append('{');
            boolean first = true;
            for (var e : json.properties()) {
                if (!first) out.append('\n').append(" ".repeat(indent + 1));
                out.append(':').append(e.getKey()).append(' ');
                display(e.getValue(), indent + 2 + e.getKey().length() + 1, out);
                first = false;
            }
            out.append('}');
            return;
        }
        // A list breaks after its leading atoms; each nested list or object goes on its own line
        out.append('(');
        int i = 0;
        for (; i < json.size() && !(json.get(i).isArray() || json.get(i).isObject()); i++) {
            if (i > 0) out.append(' ');
            out.append(inline(json.get(i)));
        }
        for (; i < json.size(); i++) {
            if (i > 0) out.append('\n').append(" ".repeat(indent + 2));
            display(json.get(i), indent + 2, out);
        }
        out.append(')');
    }

    private static String inline(JsonNode json) {
        if (json.isArray()) {
            List<String> parts = new ArrayList<>();
            json.forEach(c -> parts.add(inline(c)));
            return "(" + String.join(" ", parts) + ")";
        }
        if (json.isObject()) {
            List<String> parts = new ArrayList<>();
            json.properties().forEach(e -> parts.add(":" + e.getKey() + " " + inline(e.getValue())));
            return "{" + String.join(" ", parts) + "}";
        }
        // Bare unless it would read as another value, so the notation shows one JSON document only
        if (json.isTextual() && BARE.matcher(json.textValue()).matches() && !KEYWORDS.contains(json.textValue())) {
            return json.textValue();
        }
        return json.toString();
    }
}
