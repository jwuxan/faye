package faye.app;

import faye.engine.FlowVersion;
import faye.engine.FlowVersions;
import faye.ir.IrCodec;
import faye.ir.IrException;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Flow versions in SQLite: {@code faye_version} holds each version's canonical IR by hash, {@code
 * faye_history} the hashes a tenant published in order, and {@code faye_tenant.current} its current
 * one. A published version is never deleted, so a tenant can always roll back to it. A version only
 * stored (a previewed draft) is deleted once no tenant published it, no live flow pins it, and it is
 * older than the flow TTL (ADR 0024).
 */
final class SqliteFlowVersions implements FlowVersions {

    /** Versions kept in memory; each is read and verified once while it stays. */
    static final int CACHE_SIZE = 256;

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Duration ttl;
    private final Clock clock;

    private final Map<String, FlowVersion> cache = new Lru();

    /** Access ordered, dropping the least recently read version beyond {@link #CACHE_SIZE}. */
    private static final class Lru extends LinkedHashMap<String, FlowVersion> {
        private static final long serialVersionUID = 1L;

        Lru() {
            super(16, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, FlowVersion> eldest) {
            return size() > CACHE_SIZE;
        }
    }

    SqliteFlowVersions(JdbcClient db, TransactionTemplate tx, Duration ttl, Clock clock) {
        this.db = db;
        this.tx = tx;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public FlowVersion publish(String tenantId, FlowVersion version) {
        tx.executeWithoutResult(s -> {
            store(version);
            db.sql("INSERT INTO faye_history (tenant, hash) VALUES (?, ?) ON CONFLICT DO NOTHING")
                    .params(tenantId, version.hash())
                    .update();
            setCurrent(tenantId, version.hash());
        });
        return version;
    }

    @Override
    public void store(FlowVersion version) {
        long now = clock.millis();
        tx.executeWithoutResult(s -> {
            // Storing a version again (a draft previewed again) renews it, so the sweep below keeps it
            db.sql("INSERT INTO faye_version (hash, ir, stored_at) VALUES (?, ?, ?)"
                            + " ON CONFLICT (hash) DO UPDATE SET stored_at = excluded.stored_at")
                    .params(version.hash(), IrCodec.canonicalJson(version.flow()), now)
                    .update();
            db.sql("DELETE FROM faye_version WHERE stored_at < ?"
                            + " AND hash NOT IN (SELECT hash FROM faye_history)"
                            + " AND hash NOT IN (SELECT current FROM faye_tenant WHERE current IS NOT NULL)"
                            + " AND hash NOT IN (SELECT json_extract(state, '$.versionHash') FROM faye_flow"
                            + " WHERE expires_at > ? AND json_extract(state, '$.versionHash') IS NOT NULL)")
                    .params(now - ttl.toMillis(), now)
                    .update();
        });
    }

    @Override
    public Optional<FlowVersion> current(String tenantId) {
        return db.sql("SELECT current FROM faye_tenant WHERE id = ? AND current IS NOT NULL")
                .param(tenantId)
                .query(String.class)
                .optional()
                .flatMap(this::get);
    }

    /**
     * The version stored under {@code hash}, read through the IR reader. Its bytes must hash to
     * {@code hash}; a version in an IR format this reader no longer supports reads as absent, so a
     * flow pinned to it fails closed.
     */
    @Override
    public Optional<FlowVersion> get(String hash) {
        synchronized (cache) {
            FlowVersion cached = cache.get(hash);
            if (cached != null) return Optional.of(cached);
        }
        Optional<String> json = db.sql("SELECT ir FROM faye_version WHERE hash = ?")
                .param(hash)
                .query(String.class)
                .optional();
        if (json.isEmpty()) return Optional.empty();
        if (!IrCodec.hash(json.get()).equals(hash)) {
            throw new IllegalStateException("stored version " + hash + " hashes to " + IrCodec.hash(json.get()));
        }
        FlowVersion version;
        try {
            version = new FlowVersion(hash, IrCodec.readFlow(json.get()));
        } catch (IrException e) {
            return Optional.empty();
        }
        synchronized (cache) {
            cache.put(hash, version);
        }
        return Optional.of(version);
    }

    @Override
    public List<String> history(String tenantId) {
        return db.sql("SELECT hash FROM faye_history WHERE tenant = ? ORDER BY seq")
                .param(tenantId)
                .query(String.class)
                .list();
    }

    @Override
    public boolean rollback(String tenantId, String hash) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            if (!history(tenantId).contains(hash)) return false;
            setCurrent(tenantId, hash);
            return true;
        }));
    }

    private void setCurrent(String tenantId, String hash) {
        db.sql("INSERT INTO faye_tenant (id, current) VALUES (?, ?)"
                        + " ON CONFLICT (id) DO UPDATE SET current = excluded.current")
                .params(tenantId, hash)
                .update();
    }
}
