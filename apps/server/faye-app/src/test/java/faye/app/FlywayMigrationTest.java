package faye.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR 0020: a database the pre Flyway {@code schema.sql} made — Faye's tables, rows in them, and
 * unrelated tables standing in for Hydra's — upgrades without losing anything: Flyway baselines it
 * at 0, then applies V1 and every later migration (V2 drops the stored state of the unversioned IR
 * format, not accounts; ADR 0024).
 */
class FlywayMigrationTest {

    @TempDir
    Path dir;

    ConfigurableApplicationContext boot() {
        return new SpringApplicationBuilder(FayeApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=jdbc:sqlite:" + dir.resolve("faye.db")
                        + "?journal_mode=WAL&busy_timeout=10000&transaction_mode=IMMEDIATE");
    }

    @Test
    void aPreFlywayDatabaseMigratesKeepingItsRows() throws SQLException {
        Path db = dir.resolve("faye.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
                var statement = connection.createStatement()) {
            statement.executeUpdate(migration());
            statement.executeUpdate("INSERT INTO faye_user (subject, identifier) VALUES ('user-a', 'a@example.com')");
            statement.executeUpdate("CREATE TABLE hydra_client (id TEXT)");
            statement.executeUpdate("INSERT INTO hydra_client (id) VALUES ('hydra-1')");
        }

        try (var app = boot()) {
            var jdbc = app.getBean(JdbcTemplate.class);
            // The row written before Flyway ran survives (the test config's own accounts join it)
            assertThat(jdbc.queryForList("SELECT identifier FROM faye_user", String.class))
                    .contains("a@example.com");
            assertThat(jdbc.queryForList("SELECT id FROM hydra_client", String.class))
                    .containsExactly("hydra-1");
            List<Map<String, Object>> history =
                    jdbc.queryForList("SELECT version, success FROM faye_schema_history ORDER BY installed_rank");
            assertThat(history)
                    .extracting(row -> row.get("version"))
                    .containsExactly("0", "1", "2", "3", "4", "5", "6");
            assertThat(history).allSatisfy(row -> assertThat(row.get("success")).isIn(1, true));
        }
    }

    /**
     * A migration a database has applied is never edited: Flyway refuses to start against a
     * database whose recorded checksum no longer matches the file (it happened to a demo volume
     * migrated by a draft of V1). Each file is pinned here; a schema change is a new V{n} file, added
     * to this list.
     */
    static final Map<String, String> APPLIED = Map.of(
            "V1__faye_schema.sql", "95c4bdb40dbdb43667530de7f417d66d7d6475fa1d7a94fbe4ad59b36a1dd3ee",
            "V2__ir_format_1.sql", "0a12ad20dd8f28da0d8623f61ae3255ed2780d545534ba469897474e3be7faf9",
            "V3__publish_time.sql", "db6b9e78ba6967169cacb0b2e36f3e18f4f56670f46c510f42008bfcb75ab33b",
            "V4__hook_stored_at.sql", "be3754d645035ecbb47cd2fd7731838b964856022cc665534709e739cf6edb04",
            "V5__delivered_codes.sql", "685d4a98900f0d9ddb1543bcfb3ec212e337796a8f0304e705772bca337a4778",
            "V6__qr_grants.sql", "b46b64bc5b39aee7ac779eff742fd002360c72a34493c302c0f7af1582418d7b");

    @Test
    void migrationsAreNeverEdited() throws IOException, java.security.NoSuchAlgorithmException {
        Path dir = Path.of(System.getProperty("faye.repoRoot"), "apps/server/faye-app/src/main/resources/db/migration");
        Map<String, String> actual = new java.util.TreeMap<>();
        try (var files = java.nio.file.Files.list(dir)) {
            for (Path f : files.toList()) {
                byte[] digest =
                        java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(f));
                actual.put(f.getFileName().toString(), java.util.HexFormat.of().formatHex(digest));
            }
        }
        assertThat(actual)
                .as("an applied migration changed, or a new one is not pinned; add a new V{n} file instead")
                .isEqualTo(new java.util.TreeMap<>(APPLIED));
    }

    private static String migration() {
        try (InputStream in = FlywayMigrationTest.class.getResourceAsStream("/db/migration/V1__faye_schema.sql")) {
            if (in == null) throw new IllegalStateException("migration not on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
