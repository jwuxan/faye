package faye.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Screen protocol v1 response; see {@code schema/screen.schema.json}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScreenView(
        String flowId, String nonce, @Nullable String step, Map<String, Object> screen) implements EngineResponse {}
