package faye.engine;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What the user posted for the screen being answered. Lives only for one request; never persisted.
 *
 * @param context server issued data of the screen being answered (a WebAuthn challenge, a TOTP
 *     secret being enrolled); set by the engine, never by the user
 */
public record Submission(Map<String, Object> payload, Map<String, Object> context) {

    public static final Submission NONE = new Submission(Map.of());

    public Submission(Map<String, Object> payload) {
        this(payload, Map.of());
    }

    public Submission {
        payload = Map.copyOf(payload);
        context = Map.copyOf(context);
    }

    public @Nullable String string(String field) {
        return payload.get(field) instanceof String s ? s : null;
    }

    Submission withContext(Map<String, Object> context) {
        return new Submission(payload, context);
    }
}
