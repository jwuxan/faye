package faye.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import faye.ir.Flow;
import faye.ir.IrCodec;
import faye.ir.NodePaths;
import faye.ir.SourceMap;
import faye.ir.SourceRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The source map of a compiled flow (ADR 0027). */
class SourceMapTest {

    static Stream<Path> shippedFlows() throws IOException {
        return FlowCompilerTest.tenantFlows();
    }

    static Flow compileShipped(Path yaml) throws IOException {
        return FlowCompiler.compileWithHooks(
                        yaml.getFileName().toString(),
                        Files.readString(yaml),
                        file -> {
                            try {
                                return Optional.of(Files.readString(FlowCompilerTest.TENANT_FLOWS.resolve(file)));
                            } catch (IOException e) {
                                return Optional.empty();
                            }
                        },
                        FlowCompilerTest.WITH_APPROVE)
                .flow();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shippedFlows")
    void everyNodeOfTheShippedFlowsHasALine(Path yaml) throws IOException {
        Flow flow = compileShipped(yaml);
        SourceMap map = SourceMap.parse(SourceMap.of(flow).toJson()).orElseThrow();
        assertThat(map.origin()).isEqualTo(yaml.getFileName().toString());
        flow.entries()
                .forEach((id, entry) -> NodePaths.walk(entry.body(), (path, node) -> {
                    Optional<SourceRef> at = map.find(id, path);
                    assertThat(at).as(id + "/" + NodePaths.key(path)).isPresent();
                    assertThat(at.orElseThrow().line()).isGreaterThan(0);
                }));
    }

    @Test
    void reformattingYamlKeepsTheHashAndMovesTheLines() {
        String compact = """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { block: identifier }
                    credential: { block: password }
                """;
        String spread = """
                # A comment moves every line down
                flow: f
                version: 1

                login:
                  steps:
                    identify:
                      block: identifier

                    credential:
                      block: password
                """;
        Flow a = FlowCompiler.compile("f.yaml", compact, FlowCompilerTest.DSL);
        Flow b = FlowCompiler.compile("f.yaml", spread, FlowCompilerTest.DSL);
        assertThat(IrCodec.hash(b)).isEqualTo(IrCodec.hash(a));
        // The second step's call: seq child 1, the password block's retry, its body, then the call;
        // a block's nodes are positioned at the block
        List<Integer> use = List.of(1, 0, 0, 1);
        assertThat(SourceMap.of(a).find("login", use).orElseThrow().line()).isEqualTo(6);
        assertThat(SourceMap.of(b).find("login", use).orElseThrow().line()).isEqualTo(11);
        assertThat(SourceMap.of(b).find("login", List.of(1)).orElseThrow().line())
                .as("the step")
                .isEqualTo(10);
    }
}
