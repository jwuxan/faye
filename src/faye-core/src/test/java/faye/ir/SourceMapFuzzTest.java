package faye.ir;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * F17 (ADR 0007, ADR 0027): a stored source map is read back from the database and looked up with
 * paths from flow states and traces. Any text given to the reader ends in a map or a refusal
 * ({@code Optional.empty()}), never an exception (O1); a map it accepts writes the same text it
 * reads back (O9); and any path, however long or negative, looks up without an exception. Seeds
 * are in {@code SourceMapFuzzTestInputs/}.
 */
class SourceMapFuzzTest {

    @FuzzTest(maxDuration = "60s")
    void parse(FuzzedDataProvider data) {
        String key = data.consumeString(64);
        List<Integer> path = new ArrayList<>();
        int n = data.consumeInt(0, 8);
        for (int i = 0; i < n; i++) path.add(data.consumeInt());
        String json = data.consumeRemainingAsString();

        Optional<List<Integer>> parsed = NodePaths.parse(key);
        parsed.ifPresent(p -> assertThat(NodePaths.key(p)).as("canonical key").isEqualTo(key));

        Optional<SourceMap> map = SourceMap.parse(json);
        if (map.isEmpty()) return;
        String written = map.get().toJson();
        assertThat(SourceMap.parse(written)).as("round trip").contains(map.get());
        assertThat(SourceMap.parse(written).orElseThrow().toJson()).isEqualTo(written);
        for (String entry : map.get().entries().keySet()) {
            Optional<SourceRef> at = map.get().find(entry, path);
            at.ifPresent(r -> assertThat(r.line()).isPositive());
        }
        map.get().find(data.consumeString(8), path);
    }
}
