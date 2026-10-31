package faye.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JSON form of {@link FlowState}, shared by every store so state is always plain data. */
public final class FlowStateCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FlowStateCodec() {}

    public static String write(FlowState state) {
        try {
            return MAPPER.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static FlowState read(String json) {
        try {
            return MAPPER.readValue(json, FlowState.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unreadable flow state", e);
        }
    }
}
