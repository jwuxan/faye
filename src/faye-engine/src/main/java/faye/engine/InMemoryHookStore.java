package faye.engine;

import faye.engine.spi.HookStore;
import faye.ir.HookDef;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** {@link HookStore} for tests and single process use. */
public final class InMemoryHookStore implements HookStore {

    private final Map<String, HookDef> byHash = new ConcurrentHashMap<>();

    @Override
    public void put(HookDef def) {
        byHash.putIfAbsent(def.hash(), def);
    }

    @Override
    public Optional<HookDef> get(String hash) {
        return Optional.ofNullable(byHash.get(hash));
    }
}
