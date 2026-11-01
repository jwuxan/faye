package faye.engine;

/** A request the engine refuses; the flow state is unchanged. */
public class FlowException extends RuntimeException {

    public enum Code {
        FLOW_NOT_FOUND,
        FLOW_FINISHED,
        /** The flow is past a limit of its tenant's flow settings (ADR 0035). */
        FLOW_EXPIRED,
        STALE_NONCE,
        CONFLICT,
        INVALID_SUBMISSION,
        /** An action the waiting screen does not declare, or input it does not take (ADR 0032). */
        UNKNOWN_ACTION;

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
