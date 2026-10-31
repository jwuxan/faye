package faye.engine;

public enum HostKind {
    LOGIN,
    CONSENT,
    LOGOUT;

    /** IR section run for this kind of challenge. */
    public String section() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
