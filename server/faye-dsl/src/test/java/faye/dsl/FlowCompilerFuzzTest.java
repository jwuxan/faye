package faye.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.junit.FuzzTest;
import faye.ir.Flow;
import faye.ir.IrCodec;
import java.nio.charset.StandardCharsets;

/**
 * F2 (ADR 0007): any bytes given to the compiler end in a flow or a {@link DslException}, nothing
 * else (O1); every diagnostic points inside the source the author wrote; a compiled flow is one the
 * IR reader accepts unchanged (O9), and compiling is deterministic. Seeds are the shipped flows,
 * the diagnostic cases and hostile YAML in {@code FlowCompilerFuzzTestInputs/}.
 */
class FlowCompilerFuzzTest {

    @FuzzTest(maxDuration = "60s")
    void compile(byte[] input) {
        String yaml = new String(input, StandardCharsets.UTF_8);
        long lines = yaml.lines().count() + 1;
        Flow flow;
        try {
            flow = FlowCompiler.compile("f.yaml", yaml);
        } catch (DslException refused) {
            assertThat(refused.diagnostics()).as("a refusal says why").isNotEmpty();
            for (Diagnostic d : refused.diagnostics()) {
                assertThat(d.source().origin()).isEqualTo("f.yaml");
                assertThat(d.source().line()).as(d.toString()).isBetween(1, (int) lines);
                assertThat(d.source().column()).as(d.toString()).isPositive();
                assertThat(d.message()).as(d.toString()).isNotBlank();
            }
            return;
        }
        String canonical = IrCodec.canonicalJson(flow);
        assertThat(IrCodec.canonicalJson(IrCodec.readFlow(canonical))).isEqualTo(canonical);
        assertThat(IrCodec.canonicalJson(FlowCompiler.compile("f.yaml", yaml)))
                .as("deterministic")
                .isEqualTo(canonical);
    }
}
