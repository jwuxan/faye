package faye.app;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The key delivered codes are MACed with (ADR 0032): {@code faye.code-key} when the operator sets
 * one, else a key generated once and kept in the database, so every process on it, and a restart,
 * resumes the same flows. A key kept beside the flow state protects less if the database leaks; an
 * operator who cares sets {@code FAYE_CODE_KEY}.
 */
final class CodeKey {

    private static final String NAME = "code_mac";

    private CodeKey() {}

    static byte[] load(FayeProperties props, JdbcClient db) {
        String configured = props.codeKey();
        if (configured != null && !configured.isBlank()) {
            byte[] key = Base64.getDecoder().decode(configured.trim());
            if (key.length < 32) throw new IllegalStateException("faye.code-key must be at least 32 bytes, base64");
            return key;
        }
        byte[] fresh = new byte[32];
        new SecureRandom().nextBytes(fresh);
        db.sql("INSERT INTO faye_key (name, value) VALUES (?, ?) ON CONFLICT (name) DO NOTHING")
                .params(NAME, Base64.getEncoder().encodeToString(fresh))
                .update();
        String stored = db.sql("SELECT value FROM faye_key WHERE name = ?")
                .param(NAME)
                .query(String.class)
                .single();
        return Base64.getDecoder().decode(stored);
    }
}
