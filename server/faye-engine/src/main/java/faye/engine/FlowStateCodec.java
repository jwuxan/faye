package faye.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import java.util.Set;

/**
 * JSON form of {@link FlowState}, shared by every store so state is always plain data. The JSON
 * carries a {@code stateFormat}: what the frames mean, versioned apart from the IR format. A state
 * in a format this reader does not know reads as absent, so its flow fails closed and the person
 * starts the sign in again (docs/roadmap/ir-spec.md, Versioning).
 */
public final class FlowStateCodec {

    /** The state format this codec writes. Bump it whenever a frame's meaning changes. */
    public static final int FORMAT = 1;

    /** The state formats this codec reads: the current one and, for one release after a bump, the one before. */
    public static final Set<Integer> FORMATS = Set.of(1);

    /** Map keys sorted, so one state always writes the same bytes (ADR 0007, O9). */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private FlowStateCodec() {}

    public static String write(FlowState state) {
        ObjectNode json = MAPPER.createObjectNode().put("stateFormat", FORMAT);
        json.setAll((ObjectNode) MAPPER.valueToTree(state));
        try {
            return MAPPER.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The state, or empty if it is in a format this reader does not support. */
    public static Optional<FlowState> read(String json) {
        try {
            ObjectNode node = (ObjectNode) MAPPER.readTree(json);
            JsonNode format = node.remove("stateFormat");
            if (format == null || !format.canConvertToInt() || !FORMATS.contains(format.intValue())) {
                return Optional.empty();
            }
            return Optional.of(MAPPER.treeToValue(node, FlowState.class));
        } catch (JsonProcessingException | ClassCastException e) {
            throw new IllegalStateException("unreadable flow state", e);
        }
    }
}
