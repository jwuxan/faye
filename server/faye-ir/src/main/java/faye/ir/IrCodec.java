package faye.ir;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Reads and writes the JSON form of the IR defined by {@code schema/ir.schema.json}, and computes
 * the canonical bytes and content hash of a flow. Source refs are not part of the JSON form.
 */
public final class IrCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]*");
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*");

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

    public static Flow readFlow(JsonNode json) {
        if (!json.isArray() || json.size() < 4 || !"flow".equals(json.get(0).asText())) {
            throw new IrException("", "expected [\"flow\", id, version, section...]");
        }
        String id = text(json.get(1), "/1", ID);
        if (!json.get(2).canConvertToInt()
                || !json.get(2).isIntegralNumber()
                || json.get(2).intValue() < 1) {
            throw new IrException("/2", "version must be a positive integer");
        }
        Map<String, Node> sections = new LinkedHashMap<>();
        for (int i = 3; i < json.size(); i++) {
            JsonNode s = json.get(i);
            String at = "/" + i;
            if (!s.isArray() || s.size() != 2) {
                throw new IrException(at, "expected [section, body]");
            }
            String name = s.get(0).asText();
            if (!Flow.SECTIONS.contains(name)) {
                throw new IrException(at + "/0", "unknown section " + s.get(0));
            }
            if (sections.put(name, readNode(s.get(1), at + "/1")) != null) {
                throw new IrException(at + "/0", "duplicate section " + name);
            }
        }
        return new Flow(id, json.get(2).intValue(), sections);
    }

    public static Node readNode(JsonNode json, String at) {
        if (!json.isArray() || json.isEmpty() || !json.get(0).isTextual()) {
            throw new IrException(at, "expected a form [name, ...]");
        }
        String form = json.get(0).textValue();
        int n = json.size();
        return switch (form) {
            case "seq" -> {
                if (n < 2) throw new IrException(at, "seq needs at least one child");
                List<Node> children = new ArrayList<>();
                for (int i = 1; i < n; i++) children.add(readNode(json.get(i), at + "/" + i));
                yield new Seq(children, SourceRef.NONE);
            }
            case "step" -> {
                arity(json, at, 3, 3);
                yield new Step(text(json.get(1), at + "/1", ID), readNode(json.get(2), at + "/2"), SourceRef.NONE);
            }
            case "screen" -> {
                arity(json, at, 2, 3);
                yield new Screen(text(json.get(1), at + "/1", ID), readLiterals(json, 2, at), SourceRef.NONE);
            }
            case "call" -> {
                arity(json, at, 2, 3);
                yield new Call(text(json.get(1), at + "/1", NAME), readLiterals(json, 2, at), SourceRef.NONE);
            }
            case "hook" -> {
                arity(json, at, 3, 3);
                yield readHook(json, at);
            }
            case "choose" -> {
                if (n < 2) throw new IrException(at, "choose needs at least one option");
                // A hook may come first and pick among the options
                Hook pick = json.get(1).isArray()
                                && "hook".equals(json.get(1).path(0).asText())
                        ? (Hook) readNode(json.get(1), at + "/1")
                        : null;
                if (pick != null && n < 3) throw new IrException(at, "choose needs at least one option");
                List<Choose.Opt> options = new ArrayList<>();
                for (int i = pick == null ? 1 : 2; i < n; i++) {
                    JsonNode o = json.get(i);
                    String oat = at + "/" + i;
                    if (!o.isArray() || o.size() != 3 || !"opt".equals(o.get(0).asText())) {
                        throw new IrException(oat, "expected [\"opt\", id, body]");
                    }
                    String id = text(o.get(1), oat + "/1", ID);
                    if (options.stream().anyMatch(x -> x.id().equals(id))) {
                        throw new IrException(oat + "/1", "duplicate option " + id);
                    }
                    options.add(new Choose.Opt(id, readNode(o.get(2), oat + "/2"), SourceRef.NONE));
                }
                yield new Choose(pick, options, SourceRef.NONE);
            }
            case "when" -> {
                arity(json, at, 3, 3);
                yield new When(readPredicate(json.get(1), at + "/1"), readNode(json.get(2), at + "/2"), SourceRef.NONE);
            }
            case "retry" -> {
                arity(json, at, 3, 3);
                JsonNode attempts = json.get(1);
                if (!attempts.isIntegralNumber()
                        || attempts.intValue() < 1
                        || attempts.intValue() > Retry.MAX_ATTEMPTS) {
                    throw new IrException(at + "/1", "attempts must be an integer from 1 to " + Retry.MAX_ATTEMPTS);
                }
                yield new Retry(attempts.intValue(), readNode(json.get(2), at + "/2"), SourceRef.NONE);
            }
            case "set" -> {
                arity(json, at, 3, 3);
                yield new SetVar(
                        text(json.get(1), at + "/1", KEY), readLiteral(json.get(2), at + "/2"), SourceRef.NONE);
            }
            case "succeed" -> {
                arity(json, at, 1, 1);
                yield new Succeed(SourceRef.NONE);
            }
            case "fail" -> {
                arity(json, at, 2, 2);
                yield new Fail(text(json.get(1), at + "/1", ID), SourceRef.NONE);
            }
            default -> throw new IrException(at + "/0", "unknown form " + form);
        };
    }

    private static Hook readHook(JsonNode json, String at) {
        return new Hook(text(json.get(1), at + "/1", ID), text(json.get(2), at + "/2", Hook.HASH), SourceRef.NONE);
    }

    public static Predicate readPredicate(JsonNode json, String at) {
        if (json.isTextual()) return new Predicate.Path(path(json, at));
        if (!json.isObject() || json.size() != 1) {
            throw new IrException(at, "expected a path or an object with one operator");
        }
        var e = json.properties().iterator().next();
        String op = e.getKey();
        JsonNode arg = e.getValue();
        String here = at + "/" + op;
        return switch (op) {
            case "and", "or" -> {
                if (!arg.isArray() || arg.isEmpty()) throw new IrException(here, op + " takes a non empty list");
                List<Predicate> parts = new ArrayList<>();
                for (int i = 0; i < arg.size(); i++) parts.add(readPredicate(arg.get(i), here + "/" + i));
                yield "and".equals(op) ? new Predicate.And(parts) : new Predicate.Or(parts);
            }
            case "not" -> new Predicate.Not(readPredicate(arg, here));
            case "exists" -> new Predicate.Exists(path(arg, here));
            case "eq", "ne", "gt", "lt", "has" -> {
                if (!arg.isArray() || arg.size() != 2) throw new IrException(here, op + " takes [path, literal]");
                Object value = readLiteral(arg.get(1), here + "/1");
                if (("gt".equals(op) || "lt".equals(op)) && !(value instanceof Integer || value instanceof Long)) {
                    throw new IrException(here + "/1", op + " compares with an integer");
                }
                yield new Predicate.Compare(
                        Predicate.Op.valueOf(op.toUpperCase(java.util.Locale.ROOT)),
                        path(arg.get(0), here + "/0"),
                        value);
            }
            default -> throw new IrException(here, "unknown operator " + op);
        };
    }

    private static String path(JsonNode json, String at) {
        String path = text(json, at, NAME);
        if (!path.contains(".") || !Predicate.NAMESPACES.contains(path.substring(0, path.indexOf('.')))) {
            throw new IrException(at, "path must start with one of " + new java.util.TreeSet<>(Predicate.NAMESPACES));
        }
        return path;
    }

    private static void arity(JsonNode json, String at, int min, int max) {
        if (json.size() < min || json.size() > max) {
            throw new IrException(
                    at,
                    json.get(0).textValue() + " takes " + (min - 1) + (max > min ? " to " + (max - 1) : "")
                            + " arguments");
        }
    }

    private static String text(JsonNode json, String at, Pattern pattern) {
        if (!json.isTextual() || !pattern.matcher(json.textValue()).matches()) {
            throw new IrException(at, "expected a string matching " + pattern);
        }
        return json.textValue();
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
            if (!ID.matcher(e.getKey()).matches()) throw new IrException(here, "bad key " + e.getKey());
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
