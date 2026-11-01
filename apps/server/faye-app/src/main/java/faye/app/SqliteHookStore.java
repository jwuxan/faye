package faye.app;

import faye.engine.spi.HookStore;
import faye.ir.HookDef;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Hook definitions in table {@code faye_hook}, as canonical JSON by hash. Storing a hook again
 * renews its {@code stored_at}; {@link SqliteFlowVersions}' sweep deletes hooks older than the
 * flow TTL that no kept version's IR names, so a version that pins one can always run it.
 */
final class SqliteHookStore implements HookStore {

    /** Hooks kept in memory; each is read and verified once while it stays. */
    static final int CACHE_SIZE = 256;

    private final JdbcClient db;
    private final Clock clock;

    private final Map<String, HookDef> cache = new Lru();

    /** Access ordered, dropping the least recently read hook beyond {@link #CACHE_SIZE}. */
    private static final class Lru extends LinkedHashMap<String, HookDef> {
        private static final long serialVersionUID = 1L;

        Lru() {
            super(16, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, HookDef> eldest) {
            return size() > CACHE_SIZE;
        }
    }

    SqliteHookStore(JdbcClient db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Override
    public void put(HookDef def) {
        db.sql("INSERT INTO faye_hook (hash, def, stored_at) VALUES (?, ?, ?)"
                        + " ON CONFLICT (hash) DO UPDATE SET stored_at = excluded.stored_at")
                .params(def.hash(), def.canonicalJson(), clock.millis())
                .update();
    }

    @Override
    public Optional<HookDef> get(String hash) {
        synchronized (cache) {
            HookDef cached = cache.get(hash);
            if (cached != null) return Optional.of(cached);
        }
        Optional<String> json = db.sql("SELECT def FROM faye_hook WHERE hash = ?")
                .param(hash)
                .query(String.class)
                .optional();
        if (json.isEmpty()) return Optional.empty();
        HookDef def = HookDef.readJson(json.get());
        if (!def.hash().equals(hash)) {
            throw new IllegalStateException("stored hook " + hash + " hashes to " + def.hash());
        }
        synchronized (cache) {
            cache.put(hash, def);
        }
        return Optional.of(def);
    }
}
