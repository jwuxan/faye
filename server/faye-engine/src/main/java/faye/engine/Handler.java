package faye.engine;

import java.util.Map;
import java.util.Set;

/** A server side effect reachable from {@code (call name args)}. */
public interface Handler {

    /** e.g. {@code password.verify} */
    String name();

    /** amr classes this handler proves when it succeeds; empty if it is not a credential. */
    Set<String> amr();

    boolean bindsSubject();

    Outcome invoke(Env env, Submission input, Map<String, Object> args);
}
