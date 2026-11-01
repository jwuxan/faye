package faye.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import faye.ir.SourceMap;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link TenantConfigStore} in SQLite: {@code faye_tenant} holds each tenant's draft (YAML), settings
 * and scenario (JSON), {@code faye_version_source} the YAML of each version, and {@code
 * faye_version_source_map} the source map of that YAML (ADR 0027).
 */
final class SqliteTenantConfigStore implements TenantConfigStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final System.Logger LOG = System.getLogger(SqliteTenantConfigStore.class.getName());

    private final JdbcClient db;
    private final TransactionTemplate tx;

    SqliteTenantConfigStore(JdbcClient db, TransactionTemplate tx) {
        this.db = db;
        this.tx = tx;
    }

    @Override
    public Optional<String> draft(String tenantId) {
        return column(tenantId, "draft");
    }

    @Override
    public void saveDraft(String tenantId, String yaml) {
        saveColumn(tenantId, "draft", yaml);
    }

    @Override
    public Optional<String> source(String versionHash) {
        return db.sql("SELECT yaml FROM faye_version_source WHERE hash = ?")
                .param(versionHash)
                .query(String.class)
                .optional();
    }

    @Override
    public void saveSource(String versionHash, String yaml, SourceMap map) {
        tx.executeWithoutResult(s -> {
            int added = db.sql("INSERT INTO faye_version_source (hash, yaml) VALUES (?, ?) ON CONFLICT DO NOTHING")
                    .params(versionHash, yaml)
                    .update();
            // The map belongs to the YAML kept: only the first text's
            if (added == 1) saveSourceMap(versionHash, map);
        });
    }

    /** A stored map that does not read is logged and treated as absent. */
    @Override
    public Optional<SourceMap> sourceMap(String versionHash) {
        return db.sql("SELECT map FROM faye_version_source_map WHERE hash = ?")
                .param(versionHash)
                .query(String.class)
                .optional()
                .flatMap(json -> {
                    Optional<SourceMap> map = SourceMap.parse(json);
                    if (map.isEmpty()) {
                        LOG.log(System.Logger.Level.WARNING, "unreadable source map of version {0}", versionHash);
                    }
                    return map;
                });
    }

    @Override
    public void saveSourceMap(String versionHash, SourceMap map) {
        db.sql("INSERT INTO faye_version_source_map (hash, map) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .params(versionHash, map.toJson())
                .update();
    }

    @Override
    public void replaceSource(String versionHash, String yaml, SourceMap map) {
        tx.executeWithoutResult(s -> {
            db.sql("INSERT INTO faye_version_source (hash, yaml) VALUES (?, ?)"
                            + " ON CONFLICT (hash) DO UPDATE SET yaml = excluded.yaml")
                    .params(versionHash, yaml)
                    .update();
            // The map belongs to the YAML kept
            db.sql("INSERT INTO faye_version_source_map (hash, map) VALUES (?, ?)"
                            + " ON CONFLICT (hash) DO UPDATE SET map = excluded.map")
                    .params(versionHash, map.toJson())
                    .update();
        });
    }

    @Override
    public List<String> unmappedSources() {
        return db.sql("SELECT hash FROM faye_version_source"
                        + " WHERE hash NOT IN (SELECT hash FROM faye_version_source_map) ORDER BY hash")
                .query(String.class)
                .list();
    }

    @Override
    public Optional<TenantSettings> settings(String tenantId) {
        return column(tenantId, "settings").map(json -> {
            try {
                return JSON.readValue(json, TenantSettings.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("unreadable settings for tenant " + tenantId, e);
            }
        });
    }

    @Override
    public void saveSettings(String tenantId, TenantSettings settings) {
        saveColumn(tenantId, "settings", write(settings));
    }

    @Override
    public Optional<PreviewScenario> scenario(String tenantId) {
        return column(tenantId, "scenario").map(json -> {
            try {
                return JSON.readValue(json, PreviewScenario.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("unreadable preview scenario for tenant " + tenantId, e);
            }
        });
    }

    @Override
    public void saveScenario(String tenantId, PreviewScenario scenario) {
        saveColumn(tenantId, "scenario", write(scenario));
    }

    /** @param column one of this class's constant column names, never input */
    private Optional<String> column(String tenantId, String column) {
        return db.sql("SELECT " + column + " FROM faye_tenant WHERE id = ? AND " + column + " IS NOT NULL")
                .param(tenantId)
                .query(String.class)
                .optional();
    }

    /** @param column one of this class's constant column names, never input */
    private void saveColumn(String tenantId, String column, String value) {
        db.sql("INSERT INTO faye_tenant (id, " + column + ") VALUES (?, ?)" + " ON CONFLICT (id) DO UPDATE SET "
                        + column + " = excluded." + column)
                .params(tenantId, value)
                .update();
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
