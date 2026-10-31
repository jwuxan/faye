package faye.engine;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Result of a handler invocation. */
public sealed interface Outcome {

    /**
     * @param user fields to merge into {@code user.*}
     * @param subject subject proven by a credential handler; required iff the handler binds one
     * @param amr the subset of the handler's amr this invocation proved (a passkey without user
     *     verification proves {@code hwk} but not {@code user}); null for all of them
     */
    record Ok(
            Map<String, Object> user,
            @Nullable String subject,
            @Nullable Set<String> amr) implements Outcome {
        public Ok {
            user = Map.copyOf(user);
            amr = amr == null ? null : Set.copyOf(amr);
        }
    }

    record Fail(String error) implements Outcome {}

    static Outcome ok() {
        return new Ok(Map.of(), null, null);
    }

    static Outcome user(Map<String, Object> user) {
        return new Ok(user, null, null);
    }

    static Outcome subject(String subject) {
        return new Ok(Map.of(), subject, null);
    }

    static Outcome fail(String error) {
        return new Fail(error);
    }
}
