package faye.engine.spi;

import java.util.Locale;
import java.util.Optional;

/** The user store, read side: the engine's only view of who exists. */
public interface UserDirectory {

    /**
     * The account for an identifier the engine already normalized ({@link UserDirectory#normalize}),
     * or empty.
     */
    Optional<Account> byIdentifier(String identifier);

    /** The account for a subject, or empty. */
    Optional<Account> bySubject(String subject);

    /** How the engine normalizes a typed identifier before lookup: trim, then lowercase. */
    static String normalize(String identifier) {
        return identifier.trim().toLowerCase(Locale.ROOT);
    }
}
