package faye.app;

import faye.engine.FlowState;
import faye.engine.FlowStateCodec;
import faye.engine.FlowStore;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Flow state in table {@code faye_flow}: its {@code rev}, {@code state} and {@code expires_at}. The
 * expiry is set once at creation, so a flow expires with its challenge however often it is resumed.
 * Expired rows read as absent and are deleted when the next flow is created.
 */
final class SqliteFlowStore implements FlowStore {

    private final JdbcClient db;
    private final Duration ttl;
    private final Clock clock;

    SqliteFlowStore(JdbcClient db, Duration ttl, Clock clock) {
        this.db = db;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public Optional<FlowState> load(String flowId) {
        return db.sql("SELECT state FROM faye_flow WHERE id = ? AND expires_at > ?")
                .params(flowId, clock.millis())
                .query(String.class)
                .optional()
                .flatMap(FlowStateCodec::read);
    }

    @Override
    public void create(FlowState state) {
        long now = clock.millis();
        db.sql("DELETE FROM faye_flow WHERE expires_at <= ?").param(now).update();
        int created = db.sql("INSERT INTO faye_flow (id, rev, state, expires_at) VALUES (?, ?, ?, ?)"
                        + " ON CONFLICT (id) DO NOTHING")
                .params(state.flowId(), state.revision(), FlowStateCodec.write(state), now + ttl.toMillis())
                .update();
        if (created != 1) throw new IllegalStateException("flow exists: " + state.flowId());
    }

    @Override
    public boolean replace(FlowState next) {
        return db.sql("UPDATE faye_flow SET rev = ?, state = ? WHERE id = ? AND rev = ? AND expires_at > ?")
                        .params(
                                next.revision(),
                                FlowStateCodec.write(next),
                                next.flowId(),
                                next.revision() - 1,
                                clock.millis())
                        .update()
                == 1;
    }
}
