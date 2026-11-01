package faye.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The limits on a hook's result live in {@link HookOutput}; the IR specification quotes them in its
 * hook section, and this keeps the two the same.
 */
class HookContractTest {

    @Test
    void theHookContractQuotesTheLimitsTheEngineEnforces() throws IOException {
        String doc = Files.readString(Path.of(System.getProperty("faye.repoRoot"), "docs/ir-spec.md"));
        assertThat(doc)
                .contains("1 to " + HookOutput.MAX_VARS + " names")
                .contains("up to " + HookOutput.MAX_STRING + " characters")
                .contains("at most " + HookOutput.MAX_REASON + " characters");
    }

    @Test
    void theLimitsHold() {
        Map<String, Object> vars = new java.util.HashMap<>();
        for (int i = 0; i <= HookOutput.MAX_VARS; i++) {
            vars.put("v" + i, 1);
        }
        assertThat(HookOutput.parse(Map.of("set", vars))).isEmpty();
        assertThat(HookOutput.parse(Map.of("set", Map.of("v", "x".repeat(HookOutput.MAX_STRING + 1)))))
                .isEmpty();
        assertThat(HookOutput.parse(Map.of("fail", "r".repeat(HookOutput.MAX_REASON))))
                .isPresent();
        assertThat(HookOutput.parse(Map.of("fail", "r".repeat(HookOutput.MAX_REASON + 1))))
                .isEmpty();
        // Names and reasons follow the IR schema's key and id
        assertThat(HookOutput.parse(Map.of("set", Map.of("Bad", 1)))).isEmpty();
        assertThat(HookOutput.parse(Map.of("fail", "too-risky"))).isPresent();
        assertThat(HookOutput.parse(Map.of("fail", "Too risky"))).isEmpty();
    }
}
