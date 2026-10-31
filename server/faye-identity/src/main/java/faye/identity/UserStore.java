package faye.identity;

import java.util.Optional;
import java.util.function.UnaryOperator;

/** Accounts by normalized identifier and by subject. */
public interface UserStore {

    Optional<User> byIdentifier(String identifier);

    Optional<User> bySubject(String subject);

    /** Adds or replaces the account with this subject. */
    void save(User user);

    /** Atomically replaces the account with {@code change} applied; no-op for an unknown subject. */
    void update(String subject, UnaryOperator<User> change);
}
