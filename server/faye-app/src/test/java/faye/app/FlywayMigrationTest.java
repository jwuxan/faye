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
 * at 0, then applies V1.
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
            assertThat(history).extracting(row -> row.get("version")).containsExactly("0", "1");
            assertThat(history).allSatisfy(row -> assertThat(row.get("success")).isIn(1, true));
        }
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
