package faye.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import faye.engine.spi.QrGrants;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * QR code grants in table {@code faye_qr_grant} (ADR 0032). Every state change is one conditional
 * statement, so two requests cannot both approve, or both consume, one grant.
 */
final class SqliteQrGrants implements QrGrants {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long DAY = Duration.ofDays(1).toMillis();

    private final JdbcClient db;
    private final Clock clock;

    SqliteQrGrants(JdbcClient db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Override
    public void create(Grant g) {
        db.sql("DELETE FROM faye_qr_grant WHERE expires_at < ?")
                .param(clock.millis() - DAY)
                .update();
        db.sql("INSERT INTO faye_qr_grant (token_hash, flow_id, tenant, preview, client, number, device, expires_at,"
                        + " status, subject, amr) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(
                        g.tokenHash(),
                        g.flowId(),
                        g.tenant(),
                        g.preview(),
                        write(g.client()),
                        g.number(),
                        g.device(),
                        g.expiresAt(),
                        g.status().name(),
                        g.subject(),
                        write(g.amr()))
                .update();
    }

    @Override
    public Optional<Grant> find(String tokenHash) {
        return db.sql("SELECT * FROM faye_qr_grant WHERE token_hash = ?")
                .param(tokenHash)
                .query(SqliteQrGrants::grant)
                .optional();
    }

    @Override
    public boolean approve(String tokenHash, String subject, List<String> amr, long now) {
        int changed = db.sql("UPDATE faye_qr_grant SET status = 'APPROVED', subject = ?, amr = ?"
                        + " WHERE token_hash = ? AND status = 'PENDING' AND expires_at > ?")
                .params(subject, write(amr), tokenHash, now)
                .update();
        if (changed == 1) return true;
        // Applying the same approval again (a retried commit) is success
        return find(tokenHash)
                .filter(g -> g.status() == Status.APPROVED && subject.equals(g.subject()))
                .isPresent();
    }

    @Override
    public void refuse(String tokenHash) {
        db.sql("UPDATE faye_qr_grant SET status = 'REFUSED' WHERE token_hash = ? AND status = 'PENDING'")
                .param(tokenHash)
                .update();
    }

    @Override
    public Optional<Grant> consume(String tokenHash, String flowId, long now) {
        db.sql("UPDATE faye_qr_grant SET status = 'CONSUMED'"
                        + " WHERE token_hash = ? AND flow_id = ? AND status = 'APPROVED' AND expires_at > ?")
                .params(tokenHash, flowId, now)
                .update();
        // Consumed by this flow: this request's update, or a double submit's
        return find(tokenHash)
                .filter(g -> g.flowId().equals(flowId) && g.status() == Status.CONSUMED && now < g.expiresAt());
    }

    private static Grant grant(ResultSet rs, int row) throws SQLException {
        try {
            return new Grant(
                    rs.getString("token_hash"),
                    rs.getString("flow_id"),
                    rs.getString("tenant"),
                    rs.getBoolean("preview"),
                    JSON.readValue(rs.getString("client"), new TypeReference<Map<String, Object>>() {}),
                    rs.getInt("number"),
                    rs.getString("device"),
                    rs.getLong("expires_at"),
                    Status.valueOf(rs.getString("status").toUpperCase(Locale.ROOT)),
                    rs.getString("subject"),
                    JSON.readValue(rs.getString("amr"), new TypeReference<List<String>>() {}));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unreadable QR grant", e);
        }
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
