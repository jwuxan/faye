package faye.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import faye.identity.User;
import faye.identity.UserStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
            + " totp_secret, totp_last_step, passkeys, mfa_required, preview, phone, phone_verified, phone_codes";

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
        db.sql("INSERT INTO faye_user (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (subject) DO UPDATE SET identifier = excluded.identifier,"
                        + " name = excluded.name, tenant = excluded.tenant, email_verified = excluded.email_verified,"
                        + " password_hash = excluded.password_hash, totp_secret = excluded.totp_secret,"
                        + " totp_last_step = excluded.totp_last_step, passkeys = excluded.passkeys,"
                        + " mfa_required = excluded.mfa_required, preview = excluded.preview,"
                        + " phone = excluded.phone, phone_verified = excluded.phone_verified,"
                        + " phone_codes = excluded.phone_codes")
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
                        u.preview(),
                        u.phone(),
                        u.phoneVerified(),
                        u.phoneCodes())
                .update();
    }

    /** One statement: the number, verified, with phone codes on (ADR 0032). */
    @Override
    public void enrollPhone(String subject, String phone) {
        db.sql("UPDATE faye_user SET phone = ?, phone_verified = 1, phone_codes = 1 WHERE subject = ?")
                .params(phone, subject)
                .update();
    }

    /** One statement, so atomic: the step advances, or stays and was used by this same flow. */
    @Override
    public boolean useTotpStep(String subject, long step, String flowId) {
        return db.sql("UPDATE faye_user SET totp_last_step = ?, totp_last_flow = ?"
                                + " WHERE subject = ? AND totp_secret IS NOT NULL"
                                + " AND (totp_last_step < ? OR (totp_last_step = ? AND totp_last_flow = ?))")
                        .params(step, flowId, subject, step, step, flowId)
                        .update()
                == 1;
    }

    @Override
    public void enrollTotp(String subject, String secret, long step, String flowId) {
        db.sql("UPDATE faye_user SET totp_secret = ?, totp_last_step = ?, totp_last_flow = ? WHERE subject = ?")
                .params(secret, step, flowId, subject)
                .update();
    }

    /** In a write transaction, so the credential id cannot be taken between the check and the insert. */
    @Override
    public boolean addPasskey(String subject, User.Passkey passkey) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            Optional<String> owner = db.sql("SELECT u.subject FROM faye_user u, json_each(u.passkeys) p"
                            + " WHERE json_extract(p.value, '$.credentialId') = ?")
                    .param(passkey.credentialId())
                    .query(String.class)
                    .optional();
            if (owner.isPresent()) return owner.get().equals(subject);
            Optional<User> user = bySubject(subject);
            if (user.isEmpty()) return false;
            List<User.Passkey> all = new ArrayList<>(user.get().passkeys());
            all.add(passkey);
            db.sql("UPDATE faye_user SET passkeys = ? WHERE subject = ?")
                    .params(write(all), subject)
                    .update();
            return true;
        }));
    }

    @Override
    public void raiseSignCount(String subject, String credentialId, long count) {
        tx.executeWithoutResult(s -> bySubject(subject)
                .ifPresent(u -> db.sql("UPDATE faye_user SET passkeys = ? WHERE subject = ?")
                        .params(
                                write(u.passkeys().stream()
                                        .map(p -> p.credentialId().equals(credentialId) && count > p.signCount()
                                                ? new User.Passkey(p.credentialId(), p.attestedCredentialData(), count)
                                                : p)
                                        .toList()),
                                subject)
                        .update()));
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
                    rs.getString("tenant"),
                    rs.getString("phone"),
                    rs.getBoolean("phone_verified"),
                    rs.getBoolean("phone_codes"));
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
