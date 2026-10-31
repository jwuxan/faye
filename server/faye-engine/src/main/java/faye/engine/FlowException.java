package faye.engine;

/** A request the engine refuses; the flow state is unchanged. */
public class FlowException extends RuntimeException {

    public enum Code {
        FLOW_NOT_FOUND,
        FLOW_FINISHED,
        STALE_NONCE,
        CONFLICT,
        INVALID_SUBMISSION;

        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final Code code;

    public FlowException(Code code) {
        super(code.wireName());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
