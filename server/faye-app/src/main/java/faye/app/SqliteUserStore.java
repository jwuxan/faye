package faye.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import faye.identity.User;
import faye.identity.UserStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accounts in table {@code faye_user}, each tenant's user pool. The deploy seed writes the
 * directory's accounts; enrollments made in flows (TOTP, passkeys) are written back, so they
 * survive a restart.
 */
final class SqliteUserStore implements UserStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<User.Passkey>> PASSKEYS = new TypeReference<>() {};
    private static final String COLUMNS = "subject, identifier, name, tenant, email_verified, password_hash,"
            + " totp_secret, totp_last_step, passkeys, mfa_required, preview";

    private final JdbcClient db;
    private final TransactionTemplate tx;

    SqliteUserStore(JdbcClient db, TransactionTemplate tx) {
        this.db = db;
        this.tx = tx;
    }

    @Override
    public Optional<User> byIdentifier(String identifier) {
        return db.sql("SELECT " + COLUMNS + " FROM faye_user WHERE identifier = ?")
                .param(identifier)
                .query(SqliteUserStore::user)
                .optional();
    }

    @Override
    public Optional<User> bySubject(String subject) {
        return db.sql("SELECT " + COLUMNS + " FROM faye_user WHERE subject = ?")
                .param(subject)
                .query(SqliteUserStore::user)
                .optional();
    }

    @Override
    public List<User> all() {
        return db.sql("SELECT " + COLUMNS + " FROM faye_user ORDER BY identifier")
                .query(SqliteUserStore::user)
                .list();
    }

    @Override
    public void save(User u) {
        db.sql("INSERT INTO faye_user (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (subject) DO UPDATE SET identifier = excluded.identifier,"
                        + " name = excluded.name, tenant = excluded.tenant, email_verified = excluded.email_verified,"
                        + " password_hash = excluded.password_hash, totp_secret = excluded.totp_secret,"
                        + " totp_last_step = excluded.totp_last_step, passkeys = excluded.passkeys,"
                        + " mfa_required = excluded.mfa_required, preview = excluded.preview")
                .params(
                        u.subject(),
                        u.identifier(),
                        u.name(),
                        u.tenant(),
                        u.emailVerified(),
                        u.passwordHash(),
                        u.totpSecret(),
                        u.totpLastStep(),
                        write(u.passkeys()),
                        u.mfaRequired(),
                        u.preview())
                .update();
    }

    @Override
    public void update(String subject, UnaryOperator<User> change) {
        tx.executeWithoutResult(s -> bySubject(subject).ifPresent(u -> save(change.apply(u))));
    }

    private static User user(ResultSet rs, int row) throws SQLException {
        long lastStep = rs.getLong("totp_last_step");
        try {
            return new User(
                    rs.getString("subject"),
                    rs.getString("identifier"),
                    rs.getString("name"),
                    rs.getBoolean("email_verified"),
                    rs.getString("password_hash"),
                    rs.getString("totp_secret"),
                    lastStep,
                    JSON.readValue(rs.getString("passkeys"), PASSKEYS),
                    rs.getBoolean("mfa_required"),
                    rs.getBoolean("preview"),
                    rs.getString("tenant"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unreadable passkeys for " + rs.getString("subject"), e);
        }
    }

    private static String write(List<User.Passkey> passkeys) {
        try {
            return JSON.writeValueAsString(passkeys);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
