package faye.app;

import faye.engine.HookStore;
import faye.ir.HookDef;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Hook definitions in table {@code faye_hook}, as canonical JSON by hash. Like versions, hooks are
 * never deleted, so a version that pins one can always run it.
 */
final class SqliteHookStore implements HookStore {

    private final JdbcClient db;
    /** Hooks are immutable, so each is read and verified once. */
    private final Map<String, HookDef> cache = new ConcurrentHashMap<>();

    SqliteHookStore(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void put(HookDef def) {
        db.sql("INSERT INTO faye_hook (hash, def) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .params(def.hash(), def.canonicalJson())
                .update();
    }

    @Override
    public Optional<HookDef> get(String hash) {
        HookDef cached = cache.get(hash);
        if (cached != null) return Optional.of(cached);
        Optional<String> json = db.sql("SELECT def FROM faye_hook WHERE hash = ?")
                .param(hash)
                .query(String.class)
                .optional();
        if (json.isEmpty()) return Optional.empty();
        HookDef def = HookDef.readJson(json.get());
        if (!def.hash().equals(hash)) {
            throw new IllegalStateException("stored hook " + hash + " hashes to " + def.hash());
        }
        cache.put(hash, def);
        return Optional.of(def);
    }
}
