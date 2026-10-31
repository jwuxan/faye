package faye.ir;

/** Malformed IR. {@code at} is a JSON pointer into the IR document. */
public class IrException extends RuntimeException {

    private final String at;

    public IrException(String at, String message) {
        super(at + ": " + message);
        this.at = at;
    }

    public String at() {
        return at;
    }
}
