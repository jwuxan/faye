package faye.app;

import faye.engine.FlowVersion;
import faye.engine.FlowVersions;
import faye.ir.IrCodec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Flow versions in SQLite: {@code faye_version} holds each version's canonical IR by hash, {@code
 * faye_history} the hashes a tenant published in order, and {@code faye_tenant.current} its current
 * one. Versions are never deleted, so a flow pinned to one can always finish.
 */
final class SqliteFlowVersions implements FlowVersions {

    private final JdbcClient db;
    private final TransactionTemplate tx;
    /** Versions are immutable, so each is read and verified once. */
    private final Map<String, FlowVersion> cache = new ConcurrentHashMap<>();

    SqliteFlowVersions(JdbcClient db, TransactionTemplate tx) {
        this.db = db;
        this.tx = tx;
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
        db.sql("INSERT INTO faye_version (hash, ir) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .params(version.hash(), IrCodec.canonicalJson(version.flow()))
                .update();
    }

    @Override
    public Optional<FlowVersion> current(String tenantId) {
        return db.sql("SELECT current FROM faye_tenant WHERE id = ? AND current IS NOT NULL")
                .param(tenantId)
                .query(String.class)
                .optional()
                .flatMap(this::get);
    }

    @Override
    public Optional<FlowVersion> get(String hash) {
        FlowVersion cached = cache.get(hash);
        if (cached != null) return Optional.of(cached);
        Optional<String> json = db.sql("SELECT ir FROM faye_version WHERE hash = ?")
                .param(hash)
                .query(String.class)
                .optional();
        if (json.isEmpty()) return Optional.empty();
        FlowVersion version = FlowVersion.of(IrCodec.readFlow(json.get()));
        if (!version.hash().equals(hash)) {
            throw new IllegalStateException("stored version " + hash + " hashes to " + version.hash());
        }
        cache.put(hash, version);
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
