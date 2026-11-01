package faye.engine.spi;

import faye.ir.HookDef;
import java.util.Optional;

/** Hook definitions by hash. A definition is immutable and is kept once stored. */
public interface HookStore {

    /** Stores {@code def} under {@link HookDef#hash()} if it is new. */
    void put(HookDef def);

    Optional<HookDef> get(String hash);
}
