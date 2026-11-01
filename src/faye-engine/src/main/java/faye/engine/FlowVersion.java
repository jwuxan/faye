package faye.engine;

import faye.ir.Flow;
import faye.ir.IrCodec;

/** An immutable published flow addressed by its content hash. */
public record FlowVersion(String hash, Flow flow) {

    public static FlowVersion of(Flow flow) {
        return new FlowVersion(IrCodec.hash(flow), flow);
    }
}
