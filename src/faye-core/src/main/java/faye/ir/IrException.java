package faye.ir;

/** Malformed IR. {@code at} is a JSON pointer into the IR document. */
public class IrException extends RuntimeException {

    private final String at;
    private final String reason;

    public IrException(String at, String reason) {
        super(at + ": " + reason);
        this.at = at;
        this.reason = reason;
    }

    public String at() {
        return at;
    }

    /** The message without the pointer. */
    public String reason() {
        return reason;
    }
}
