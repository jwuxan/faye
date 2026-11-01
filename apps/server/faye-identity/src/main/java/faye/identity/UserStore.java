package faye.identity;

import java.util.List;
import java.util.Optional;

/**
 * Accounts by normalized identifier and by subject. Every write a handler makes is one of the
 * atomic operations below, so a store guarantees the conditions the handlers rely on (ADR 0024).
 */
public interface UserStore {

    Optional<User> byIdentifier(String identifier);

    Optional<User> bySubject(String subject);

    /** Every account, for the Admin UI's read only list. */
    List<User> all();

    /** Adds or replaces the account with this subject. */
    void save(User user);

    /**
     * Records that {@code flowId} used TOTP time step {@code step}, so no other flow can use it: a
     * guard, owned by the flow. Atomic.
     *
     * @return true if the step is newer than the last one used, or is the last one used and this
     *     same flow used it; false otherwise, and for an unknown subject or one without TOTP
     */
    boolean useTotpStep(String subject, long step, String flowId);

    /** Stores a TOTP secret and marks {@code step}, the one its enrollment code was in, as used by {@code flowId}. */
    void enrollTotp(String subject, String secret, long step, String flowId);

    /**
     * Adds a passkey to the account. Atomic and idempotent: adding a credential id the account
     * already has changes nothing.
     *
     * @return false if another account has this credential id, or the subject is unknown
     */
    boolean addPasskey(String subject, User.Passkey passkey);

    /**
     * Sets a phone number the person proved they hold as verified and turns phone codes on, in one
     * write (ADR 0032). Idempotent; nothing for an unknown subject.
     */
    void enrollPhone(String subject, String phone);

    /** Raises a passkey's stored sign count to {@code count} if that is higher; never lowers it. */
    void raiseSignCount(String subject, String credentialId, long count);
}
