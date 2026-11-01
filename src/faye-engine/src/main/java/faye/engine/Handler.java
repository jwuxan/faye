package faye.engine;

import org.jspecify.annotations.Nullable;

/**
 * A built in, reviewed server side operation reachable from {@code (call name args)}: the only
 * place a credential is verified, a subject bound, or an account changed (charter principle 2).
 */
public interface Handler {

    /** e.g. {@code password.verify} */
    String name();

    HandlerMeta meta();

    Outcome invoke(Invocation call);

    /**
     * Applies an {@link Effect} this handler returned, once the flow state carrying it is saved.
     * Idempotent: applying the same effect twice is the same as once.
     *
     * @return null when applied, else the failure code that ends the flow (a precondition no longer
     *     holds)
     */
    default @Nullable String commit(Effect effect) {
        throw new UnsupportedOperationException(name() + " declares no write effect");
    }
}
