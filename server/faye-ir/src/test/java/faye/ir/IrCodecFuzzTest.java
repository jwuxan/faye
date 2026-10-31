package faye.ir;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.nio.charset.StandardCharsets;

/**
 * F1 (ADR 0007): any bytes given to the IR reader end in a flow or an {@link IrException}, nothing
 * else (O1); a flow it accepts survives a round trip with a stable hash (O9); and the reader agrees
 * with the schema validator on which documents are flows (O10). Seeds are the conformance flows and
 * hostile shapes in {@code IrCodecFuzzTestInputs/}.
 */
class IrCodecFuzzTest {

    @FuzzTest(maxDuration = "60s")
    void readFlow(byte[] input) {
        String json = new String(input, StandardCharsets.UTF_8);
        Flow flow;
        try {
            flow = IrCodec.readFlow(json);
        } catch (IrException refused) {
            return;
        }
        String canonical = IrCodec.canonicalJson(flow);
        Flow again = IrCodec.readFlow(canonical);
        assertThat(IrCodec.canonicalJson(again)).as("round trip").isEqualTo(canonical);
        assertThat(IrCodec.hash(again)).as("hash is stable").isEqualTo(IrCodec.hash(flow));
        assertThat(IrCodec.hash(canonical)).isEqualTo(IrCodec.hash(flow));
        assertThat(IrCodec.errors(IrCodec.toJson(flow)))
                .as("schema accepts what the reader wrote")
                .isEmpty();
    }
}
