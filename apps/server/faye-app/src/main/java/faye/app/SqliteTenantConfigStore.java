package faye.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link TenantConfigStore} in SQLite: {@code faye_tenant} holds each tenant's draft (YAML), settings
 * and scenario (JSON), and {@code faye_version_source} the YAML of each version.
 */
final class SqliteTenantConfigStore implements TenantConfigStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient db;

    SqliteTenantConfigStore(JdbcClient db) {
        this.db = db;
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
    public void saveSource(String versionHash, String yaml) {
        db.sql("INSERT INTO faye_version_source (hash, yaml) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .params(versionHash, yaml)
                .update();
    }

    @Override
    public void replaceSource(String versionHash, String yaml) {
        db.sql("INSERT INTO faye_version_source (hash, yaml) VALUES (?, ?)"
                        + " ON CONFLICT (hash) DO UPDATE SET yaml = excluded.yaml")
                .params(versionHash, yaml)
                .update();
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
