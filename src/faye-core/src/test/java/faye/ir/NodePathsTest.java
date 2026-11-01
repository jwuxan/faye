package faye.ir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NodePathsTest {

    /** Every IR form, nested so each kind of child index appears: seq, step, when, retry, choose. */
    static final Node EVERY_FORM = java.util.Objects.requireNonNull(
                    IrCodec.readFlow("""
            ["flow", 1, "every-form", 1,
              ["entry", "login", "authenticate",
                ["seq",
                  ["step", "a", ["seq", ["screen", "identifier"], ["call", "identity.resolve"]]],
                  ["set", "x", "y"],
                  ["when", {"eq": ["vars.x", "y"]},
                    ["retry", 2, ["hook", "h", "sha256:%s"]]],
                  ["choose",
                    [["opt", "p", ["fail", "nope"]],
                     ["opt", "q", ["succeed"], {"guard": {"eq": ["vars.x", "z"]}}]]],
                  ["succeed"]]]]
            """.formatted("0".repeat(64))).entries().get("login"))
            .body();

    @Test
    void walkVisitsEveryNodeAtThePathFindNames() {
        List<String> seen = new ArrayList<>();
        NodePaths.walk(EVERY_FORM, (path, node) -> {
            assertThat(NodePaths.find(EVERY_FORM, path)).containsSame(node);
            seen.add(NodePaths.key(path) + " " + node.getClass().getSimpleName());
        });
        assertThat(seen)
                .containsExactly(
                        " Seq",
                        "0 Step",
                        "0.0 Seq",
                        "0.0.0 Screen",
                        "0.0.1 Call",
                        "1 SetVar",
                        "2 When",
                        "2.0 Retry",
                        "2.0.0 Hook",
                        "3 Choose",
                        "3.0 Fail",
                        "3.1 Succeed",
                        "4 Succeed");
    }

    @Test
    void aPathPastALeafOrOutOfRangeNamesNoNode() {
        assertThat(NodePaths.find(EVERY_FORM, List.of(5))).isEmpty();
        assertThat(NodePaths.find(EVERY_FORM, List.of(-1))).isEmpty();
        assertThat(NodePaths.find(EVERY_FORM, List.of(1, 0))).isEmpty();
        assertThat(NodePaths.find(EVERY_FORM, List.of(0, 1))).isEmpty();
        assertThatThrownBy(() -> NodePaths.resolve(EVERY_FORM, List.of(9))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keysRoundTripAndOnlyTheCanonicalSpellingReads() {
        for (List<Integer> path : List.of(List.<Integer>of(), List.of(0), List.of(3, 1), List.of(10, 0, 2))) {
            assertThat(NodePaths.parse(NodePaths.key(path))).contains(path);
        }
        for (String bad : List.of(".", "0.", ".0", "01", "-1", "+1", "a", "1..2", " 1", "1234567890")) {
            assertThat(NodePaths.parse(bad)).as(bad).isEmpty();
        }
        assertThat(NodePaths.parse("0" + ".0".repeat(NodePaths.MAX_DEPTH))).isEmpty();
        assertThat(NodePaths.parse("0" + ".0".repeat(NodePaths.MAX_DEPTH - 1))).isPresent();
    }

    @Test
    void aSourceMapPositionsEveryNodeWithALineAndNothingElse() {
        Flow flow = new Flow(
                "f",
                1,
                Map.of(
                        "login",
                        new Entry(
                                Entry.Kind.AUTHENTICATE,
                                new Seq(
                                        List.of(
                                                new Fail("x", new SourceRef("f.yaml", 4, 7)),
                                                new Succeed(SourceRef.NONE),
                                                new Succeed(new SourceRef("other.yaml", 9, 1))),
                                        new SourceRef("f.yaml", 2, 1)))));
        SourceMap map = SourceMap.of(flow);
        assertThat(map.origin()).isEqualTo("f.yaml");
        assertThat(map.find("login", List.of())).contains(new SourceRef("f.yaml", 2, 1));
        assertThat(map.find("login", List.of(0))).contains(new SourceRef("f.yaml", 4, 7));
        assertThat(map.find("login", List.of(1))).isEmpty();
        assertThat(map.find("login", List.of(2))).as("another file").isEmpty();
        assertThat(map.find("consent", List.of())).isEmpty();
        assertThat(SourceMap.parse(map.toJson())).contains(map);
        assertThat(map.toJson())
                .isEqualTo("{\"format\":1,\"origin\":\"f.yaml\",\"entries\":{\"login\":{\"\":[2,1],\"0\":[4,7]}}}");
    }

    @Test
    void aMapThatIsNotOneThisFormatWroteDoesNotRead() {
        for (String bad : List.of(
                "",
                "[]",
                "{}",
                "{\"format\":2,\"origin\":\"\",\"entries\":{}}",
                "{\"format\":1,\"origin\":1,\"entries\":{}}",
                "{\"format\":1,\"origin\":\"\",\"entries\":[]}",
                "{\"format\":1,\"origin\":\"\",\"entries\":{},\"x\":1}",
                "{\"format\":1,\"origin\":\"\",\"entries\":{\"e\":{\"01\":[1,1]}}}",
                "{\"format\":1,\"origin\":\"\",\"entries\":{\"e\":{\"0\":[0,1]}}}",
                "{\"format\":1,\"origin\":\"\",\"entries\":{\"e\":{\"0\":[1]}}}",
                "{\"format\":1,\"origin\":\"\",\"entries\":{\"e\":{\"0\":[1,1.5]}}}",
                "{\"format\":1,\"origin\":\"\",\"entries\":{\"e\":{\"0\":[1,1],\"0\":[2,2]}}}",
                "{\"format\":1,\"origin\":\"\",\"entries\":{\"e\":{\"0\":[1,99999999999]}}}")) {
            assertThat(SourceMap.parse(bad)).as(bad).isEqualTo(Optional.empty());
        }
        assertThat(SourceMap.parse("{\"format\":1,\"origin\":\"\",\"entries\":{}}"))
                .contains(SourceMap.EMPTY);
    }
}
