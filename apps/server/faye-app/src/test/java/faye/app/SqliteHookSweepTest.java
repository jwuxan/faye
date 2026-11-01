package faye.app;

import static org.assertj.core.api.Assertions.assertThat;

import faye.engine.FlowVersion;
import faye.ir.HookDef;
import faye.ir.IrCodec;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The version sweep's hook side: a hook only a swept preview draft pinned goes with it, and a hook
 * a kept version still names stays. The IR holds hook hashes verbatim, so the sweep matches them
 * inside kept versions' IR.
 */
class SqliteHookSweepTest {

    @TempDir
    Path dir;

    ConfigurableApplicationContext boot() {
        return new SpringApplicationBuilder(FayeApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=jdbc:sqlite:" + dir.resolve("faye.db")
                        + "?journal_mode=WAL&busy_timeout=10000&transaction_mode=IMMEDIATE");
    }

    static final class MutableClock extends Clock {
        long now;

        void advance(long millis) {
            now += millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public long millis() {
            return now;
        }
    }

    static FlowVersion versionWith(String hash, String id) {
        return FlowVersion.of(IrCodec.readFlow("[\"flow\",1,\"" + id + "\",1,"
                + "[\"entry\",\"login\",\"authenticate\",[\"seq\",[\"hook\",\"h\",\"" + hash
                + "\"],[\"fail\",\"x\"]]]]"));
    }

    @Test
    void aSweptDraftsHooksAreDeletedWhileAPublishedVersionsStay() {
        try (var app = boot()) {
            JdbcClient db = app.getBean(JdbcClient.class);
            TransactionTemplate tx = app.getBean(TransactionTemplate.class);
            MutableClock clock = new MutableClock();
            Duration ttl = Duration.ofDays(1);
            var versions = new SqliteFlowVersions(db, tx, ttl, clock);
            var hooks = new SqliteHookStore(db, clock);
            HookDef draft = new HookDef("export default () => ({draft: true})", List.of(), 200);
            HookDef live = new HookDef("export default () => ({live: true})", List.of(), 200);
            hooks.put(draft);
            hooks.put(live);
            // A previewed draft stores its version without publishing it
            versions.store(versionWith(draft.hash(), "draft"));
            // Past the TTL, the publish that keeps the live version sweeps the draft and its hook
            clock.advance(ttl.toMillis() + 1);
            versions.publish("acme", versionWith(live.hash(), "live"));
            assertThat(db.sql("SELECT hash FROM faye_hook WHERE hash = ?")
                            .param(draft.hash())
                            .query(String.class)
                            .optional())
                    .isEmpty();
            assertThat(db.sql("SELECT hash FROM faye_hook WHERE hash = ?")
                            .param(live.hash())
                            .query(String.class)
                            .optional())
                    .contains(live.hash());
        }
    }
}
