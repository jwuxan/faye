package faye.app;

import faye.engine.spi.CodeOutbox;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox of delivered codes, table {@code faye_outbox} (ADR 0032): the request path records a
 * row and returns; {@link #drain} hands pending rows to their sender. It holds the limits that span
 * flows, counted over the rows themselves: per destination 5 codes an hour and 10 a day, and per
 * tenant and channel the operator's hourly ceiling. Every row counts, delivered or not, so an
 * unknown account reaches a limit like a real one.
 */
final class SqliteCodeOutbox implements CodeOutbox {

    static final int PER_DESTINATION_HOUR = 5;
    static final int PER_DESTINATION_DAY = 10;
    /** Attempts before a code is given up on. */
    static final int ATTEMPTS = 3;

    private static final long HOUR = Duration.ofHours(1).toMillis();
    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final System.Logger LOG = System.getLogger(SqliteCodeOutbox.class.getName());

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final CodeDelivery delivery;
    private final Clock clock;

    SqliteCodeOutbox(JdbcClient db, TransactionTemplate tx, CodeDelivery delivery, Clock clock) {
        this.db = db;
        this.tx = tx;
        this.delivery = delivery;
        this.clock = clock;
    }

    /** One write transaction: the counts and the insert cannot interleave with another send. */
    @Override
    public boolean enqueue(String tenant, Channel channel, @Nullable String destination, String limitKey, String code) {
        if (!delivery.configured(tenant, channel)) {
            throw new IllegalStateException("tenant " + tenant + " has no " + channel.wireName() + " delivery");
        }
        long now = clock.millis();
        return Boolean.TRUE.equals(tx.execute(s -> {
            long hour = count("limit_key = ? AND created_at > ?", limitKey, now - HOUR);
            long day = count("limit_key = ? AND created_at > ?", limitKey, now - DAY);
            long tenantHour =
                    count("tenant = ? AND channel = ? AND created_at > ?", tenant, channel.wireName(), now - HOUR);
            if (hour >= PER_DESTINATION_HOUR
                    || day >= PER_DESTINATION_DAY
                    || tenantHour >= delivery.perHour(tenant, channel)) {
                return false;
            }
            boolean discard = destination == null;
            db.sql("INSERT INTO faye_outbox (tenant, channel, destination, limit_key, code, created_at, state)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(
                            tenant,
                            channel.wireName(),
                            destination,
                            limitKey,
                            discard ? null : code,
                            now,
                            discard ? "discard" : "pending")
                    .update();
            return true;
        }));
    }

    private long count(String where, Object... params) {
        return db.sql("SELECT count(*) FROM faye_outbox WHERE " + where)
                .params(params)
                .query(Long.class)
                .single();
    }

    private record Pending(long id, String tenant, String channel, String destination, String code, int attempts) {}

    /**
     * Delivers what is pending, oldest first: a delivered code is cleared, a failed one retried up
     * to {@link #ATTEMPTS} times, then cleared. Rows older than a day are deleted.
     *
     * @return how many codes were delivered
     */
    int drain() {
        db.sql("DELETE FROM faye_outbox WHERE created_at < ?")
                .param(clock.millis() - DAY)
                .update();
        List<Pending> pending = db.sql("SELECT id, tenant, channel, destination, code, attempts FROM faye_outbox"
                        + " WHERE state = 'pending' ORDER BY id LIMIT 50")
                .query((rs, row) -> new Pending(
                        rs.getLong("id"),
                        rs.getString("tenant"),
                        rs.getString("channel"),
                        rs.getString("destination"),
                        rs.getString("code"),
                        rs.getInt("attempts")))
                .list();
        int sent = 0;
        for (Pending p : pending) {
            Channel channel = Channel.valueOf(p.channel().toUpperCase(java.util.Locale.ROOT));
            try {
                delivery.sender(p.tenant(), channel)
                        .send(new CodeDelivery.Message(p.tenant(), channel, p.destination(), p.code()));
                db.sql("UPDATE faye_outbox SET state = 'sent', code = NULL, attempts = attempts + 1 WHERE id = ?")
                        .param(p.id())
                        .update();
                sent++;
            } catch (Exception e) {
                boolean last = p.attempts() + 1 >= ATTEMPTS;
                // Never the code or the destination in a log line
                LOG.log(
                        System.Logger.Level.WARNING,
                        "{0} delivery for tenant {1} failed ({2}){3}",
                        p.channel(),
                        p.tenant(),
                        e.getClass().getSimpleName(),
                        last ? "; given up" : "");
                db.sql("UPDATE faye_outbox SET attempts = attempts + 1"
                                + (last ? ", state = 'failed', code = NULL" : "")
                                + " WHERE id = ?")
                        .param(p.id())
                        .update();
            }
        }
        return sent;
    }
}
