package faye.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import faye.engine.TraceEvent;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link TraceStore} in table {@code faye_trace}: one row per event, numbered per tenant, holding
 * the event as JSON. A tenant's latest {@value #FEED} events make its feed; a flow's events are kept
 * for the TTL after its last one, then deleted once they have also left the feed.
 */
final class SqliteTraceStore implements TraceStore {

    /** Events kept in a tenant's feed; followers read it four times a second. */
    static final int FEED = 200;

    /** Entries a newer server wrote may carry fields this one does not know. */
    private static final ObjectMapper JSON =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** An entry without its number, which the table assigns. */
    private record Entry(String tenantId, String flowId, long at, TraceEvent event) {}

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Duration ttl;
    private final Clock clock;

    /** @param ttl how long a flow's trace is kept after its last event */
    SqliteTraceStore(JdbcClient db, TransactionTemplate tx, Duration ttl, Clock clock) {
        this.db = db;
        this.tx = tx;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public void append(String tenantId, String flowId, TraceEvent event) {
        long now = clock.millis();
        String json;
        try {
            json = JSON.writeValueAsString(new Entry(tenantId, flowId, now, event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        tx.executeWithoutResult(s -> {
            long seq = latest(tenantId) + 1;
            long expires = now + ttl.toMillis();
            db.sql("INSERT INTO faye_trace (tenant, seq, flow_id, entry, expires_at) VALUES (?, ?, ?, ?, ?)")
                    .params(tenantId, seq, flowId, json, expires)
                    .update();
            db.sql("UPDATE faye_trace SET expires_at = ? WHERE flow_id = ?")
                    .params(expires, flowId)
                    .update();
            db.sql("DELETE FROM faye_trace WHERE tenant = ? AND seq <= ? AND expires_at <= ?")
                    .params(tenantId, seq - FEED, now)
                    .update();
        });
    }

    @Override
    public List<Recorded> flow(String flowId) {
        return read(db.sql("SELECT seq, entry FROM faye_trace WHERE flow_id = ? AND expires_at > ? ORDER BY seq")
                .params(flowId, clock.millis()));
    }

    @Override
    public List<Recorded> feed(String tenantId, long after) {
        return read(db.sql("SELECT seq, entry FROM faye_trace WHERE tenant = ? AND seq > ? AND seq > ? ORDER BY seq")
                .params(tenantId, after, latest(tenantId) - FEED));
    }

    @Override
    public long latest(String tenantId) {
        return db.sql("SELECT COALESCE(MAX(seq), 0) FROM faye_trace WHERE tenant = ?")
                .param(tenantId)
                .query(Long.class)
                .single();
    }

    /** Entries in order; one that cannot be read is left out rather than hiding the rest. */
    private static List<Recorded> read(JdbcClient.StatementSpec query) {
        return query.query((rs, n) -> parse(rs.getLong("seq"), rs.getString("entry"))).list().stream()
                .flatMap(Optional::stream)
                .toList();
    }

    private static Optional<Recorded> parse(long seq, String json) {
        try {
            Entry e = JSON.readValue(json, Entry.class);
            return Optional.of(new Recorded(seq, e.tenantId(), e.flowId(), e.at(), e.event()));
        } catch (JsonProcessingException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
