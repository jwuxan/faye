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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads and writes the JSON form of the IR defined by {@code schema/ir.schema.json}, and computes
 * the canonical bytes and content hash of a flow. Source refs are not part of the JSON form.
 */
public final class IrCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    /** The grammar of the read side, shipped on the classpath from the repo's schema/ directory. */
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(
            SpecVersion.VersionFlag.V202012,
            b -> b.schemaMappers(m -> m.mapPrefix("https://faye.invalid/schema/", "classpath:")));

    private static final JsonSchema SCHEMA =
            FACTORY.getSchema(SchemaLocation.of("https://faye.invalid/schema/ir.schema.json"));
    private static final JsonSchema PREDICATE =
            FACTORY.getSchema(SchemaLocation.of("https://faye.invalid/schema/ir.schema.json#/$defs/predicate"));

    private IrCodec() {}

    // ---- write ----

    public static ArrayNode toJson(Flow flow) {
        ArrayNode out = JSON.arrayNode().add("flow").add(flow.id()).add(flow.version());
        flow.sections()
                .forEach((name, body) -> out.add(JSON.arrayNode().add(name).add(toJson(body))));
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
                out.add("choose");
                if (c.pick() != null) out.add(toJson(c.pick()));
                c.options()
                        .forEach(o ->
                                out.add(JSON.arrayNode().add("opt").add(o.id()).add(toJson(o.body()))));
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
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson(flow).getBytes(StandardCharsets.UTF_8));
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
        check(SCHEMA, json, "");
        Map<String, Node> sections = new LinkedHashMap<>();
        for (int i = 3; i < json.size(); i++) {
            JsonNode s = json.get(i);
            String at = "/" + i;
            String name = s.get(0).asText();
            if (sections.put(name, readNode(s.get(1), at + "/1")) != null) {
                throw new IrException(at + "/0", "duplicate section " + name);
            }
        }
        return new Flow(json.get(1).asText(), json.get(2).intValue(), sections);
    }

    /**
     * Validates {@code json} against {@code schema}; on failure throws with {@code at} plus the
     * deepest reported instance location as the pointer.
     */
    private static void check(JsonSchema schema, JsonNode json, String at) {
        Set<ValidationMessage> errors = schema.validate(json);
        if (!errors.isEmpty()) {
            ValidationMessage deepest = errors.stream()
                    .max(java.util.Comparator.comparingInt(
                            e -> e.getInstanceLocation().getNameCount()))
                    .orElseThrow();
            throw new IrException(at + pointer(deepest), deepest.getMessage());
        }
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

    /** Maps a validated form to its record. The schema has already checked shape and arity. */
    public static Node readNode(JsonNode json, String at) {
        String form = json.get(0).textValue();
        int n = json.size();
        return switch (form) {
            case "seq" -> {
                List<Node> children = new ArrayList<>();
                for (int i = 1; i < n; i++) children.add(readNode(json.get(i), at + "/" + i));
                yield new Seq(children, SourceRef.NONE);
            }
            case "step" -> new Step(json.get(1).textValue(), readNode(json.get(2), at + "/2"), SourceRef.NONE);
            case "screen" -> new Screen(json.get(1).textValue(), readLiterals(json, 2, at), SourceRef.NONE);
            case "call" -> new Call(json.get(1).textValue(), readLiterals(json, 2, at), SourceRef.NONE);
            case "hook" -> new Hook(json.get(1).textValue(), json.get(2).textValue(), SourceRef.NONE);
            case "choose" -> {
                // A hook may come first and pick among the options
                Hook pick = json.get(1).isArray()
                                && "hook".equals(json.get(1).path(0).asText())
                        ? (Hook) readNode(json.get(1), at + "/1")
                        : null;
                List<Choose.Opt> options = new ArrayList<>();
                for (int i = pick == null ? 1 : 2; i < n; i++) {
                    JsonNode o = json.get(i);
                    String oat = at + "/" + i;
                    String id = o.get(1).textValue();
                    // What the schema cannot express: option ids are unique
                    if (options.stream().anyMatch(x -> x.id().equals(id))) {
                        throw new IrException(oat + "/1", "duplicate option " + id);
                    }
                    options.add(new Choose.Opt(id, readNode(o.get(2), oat + "/2"), SourceRef.NONE));
                }
                yield new Choose(pick, options, SourceRef.NONE);
            }
            case "when" -> new When(mapPredicate(json.get(1)), readNode(json.get(2), at + "/2"), SourceRef.NONE);
            case "retry" -> new Retry(json.get(1).intValue(), readNode(json.get(2), at + "/2"), SourceRef.NONE);
            case "set" -> new SetVar(json.get(1).textValue(), readLiteral(json.get(2), at + "/2"), SourceRef.NONE);
            case "succeed" -> new Succeed(SourceRef.NONE);
            case "fail" -> new Fail(json.get(1).textValue(), SourceRef.NONE);
            default -> throw new IrException(at + "/0", "unknown form " + form);
        };
    }

    /**
     * Validates {@code json} against {@code #/$defs/predicate} in the schema, then maps it to
     * records. Called directly by the DSL compiler for 'if' predicates, bypassing {@link
     * #readFlow}, so it validates on its own; {@code at} anchors reported errors.
     */
    public static Predicate readPredicate(JsonNode json, String at) {
        check(PREDICATE, json, at);
        return mapPredicate(json);
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
}
