package faye.hooks;

/** A hook could not produce a result: missing, threw, timed out, or ran out of memory. */
public class HookException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HookException(String message) {
        super(message);
    }

    public HookException(String message, Throwable cause) {
        super(message, cause);
    }
}
