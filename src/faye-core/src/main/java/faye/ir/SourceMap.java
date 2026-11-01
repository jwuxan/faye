package faye.ir;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where each node of a compiled flow came from, kept apart from the IR so positions never enter the
 * canonical IR or its hash (ADR 0027). Nodes are named by entry id and {@link NodePaths} path, the
 * addressing the engine reports at run time; a node the compiler gave no line is absent.
 *
 * <p>JSON form: {@code {"format": 1, "origin": "acme.yaml", "entries": {"login": {"": [3, 3], "0":
 * [5, 5]}}}}, a line and column per path key, and one origin per map (a flow compiles from one
 * file).
 *
 * @param origin the file every position is in; empty for a map with no positions
 * @param entries for each entry id, the position of each node by {@link NodePaths#key}
 */
public record SourceMap(String origin, Map<String, Map<String, Position>> entries) {

    /** The JSON format {@link #toJson} writes and {@link #parse} reads. */
    public static final int FORMAT = 1;

    /** The longest JSON {@link #parse} reads, in characters. */
    public static final int MAX_LENGTH = 4 * IrCodec.MAX_LENGTH;

    /** A map with no positions: every lookup is empty. */
    public static final SourceMap EMPTY = new SourceMap("", Map.of());

    private static final ObjectMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(8)
                            .maxDocumentLength(MAX_LENGTH)
                            .maxNumberLength(10)
                            .maxStringLength(MAX_LENGTH)
                            .build())
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build())
            .build();

    /** A line and column, both from 1. */
    public record Position(int line, int column) {
        public Position {
            if (line < 1 || column < 1) throw new IllegalArgumentException("position " + line + ":" + column);
        }
    }

    public SourceMap {
        Map<String, Map<String, Position>> copy = new LinkedHashMap<>();
        entries.forEach((id, nodes) -> copy.put(id, Collections.unmodifiableMap(new LinkedHashMap<>(nodes))));
        entries = Collections.unmodifiableMap(copy);
    }

    /**
     * The positions of every node of {@code flow} that has one. The origin is the first positioned
     * node's; a node positioned in another file is left out.
     */
    public static SourceMap of(Flow flow) {
        String[] origin = {""};
        Map<String, Map<String, Position>> entries = new LinkedHashMap<>();
        flow.entries().forEach((id, entry) -> {
            Map<String, Position> nodes = new LinkedHashMap<>();
            NodePaths.walk(entry.body(), (path, node) -> {
                SourceRef at = node.source();
                if (at.line() < 1 || at.column() < 1) return;
                if (origin[0].isEmpty()) origin[0] = at.origin();
                if (at.origin().equals(origin[0])) {
                    nodes.put(NodePaths.key(path), new Position(at.line(), at.column()));
                }
            });
            entries.put(id, nodes);
        });
        return new SourceMap(origin[0], entries);
    }

    /** Where the node at {@code path} of entry {@code entry} came from; empty when the map has no line for it. */
    public Optional<SourceRef> find(String entry, List<Integer> path) {
        Map<String, Position> nodes = entries.get(entry);
        if (nodes == null) return Optional.empty();
        Position at = nodes.get(NodePaths.key(path));
        return at == null ? Optional.empty() : Optional.of(new SourceRef(origin, at.line(), at.column()));
    }

    public String toJson() {
        ObjectNode json = MAPPER.createObjectNode().put("format", FORMAT).put("origin", origin);
        ObjectNode out = json.putObject("entries");
        entries.forEach((id, nodes) -> {
            ObjectNode entry = out.putObject(id);
            nodes.forEach((key, at) -> entry.putArray(key).add(at.line()).add(at.column()));
        });
        try {
            return MAPPER.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The map {@link #toJson} wrote; empty for anything else (another format, a path key that is not
     * canonical, a position below 1), never an exception. A stored map that does not read is treated
     * as absent: its version's failures fall back to step ids.
     */
    public static Optional<SourceMap> parse(String json) {
        if (json.length() > MAX_LENGTH) return Optional.empty();
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException | RuntimeException e) {
            return Optional.empty();
        }
        if (!(root instanceof ObjectNode object) || object.size() != 3) return Optional.empty();
        JsonNode format = object.get("format");
        JsonNode origin = object.get("origin");
        if (format == null || !format.isInt() || format.intValue() != FORMAT) return Optional.empty();
        if (origin == null || !origin.isTextual()) return Optional.empty();
        if (!(object.get("entries") instanceof ObjectNode entryNodes)) return Optional.empty();
        Map<String, Map<String, Position>> entries = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : entryNodes.properties()) {
            if (!(entry.getValue() instanceof ObjectNode nodeMap)) return Optional.empty();
            Map<String, Position> nodes = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> node : nodeMap.properties()) {
                if (NodePaths.parse(node.getKey()).isEmpty()) return Optional.empty();
                Optional<Position> at = position(node.getValue());
                if (at.isEmpty()) return Optional.empty();
                nodes.put(node.getKey(), at.get());
            }
            entries.put(entry.getKey(), nodes);
        }
        return Optional.of(new SourceMap(origin.textValue(), entries));
    }

    private static Optional<Position> position(JsonNode json) {
        if (!(json instanceof ArrayNode pair) || pair.size() != 2) return Optional.empty();
        JsonNode line = pair.get(0);
        JsonNode column = pair.get(1);
        if (!line.isInt() || !column.isInt() || line.intValue() < 1 || column.intValue() < 1) {
            return Optional.empty();
        }
        return Optional.of(new Position(line.intValue(), column.intValue()));
    }
}
