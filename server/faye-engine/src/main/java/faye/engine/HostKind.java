package faye.engine;

/** The kinds of challenge a host hands Faye. {@link EntrySelector} maps each to an IR entry. */
public enum HostKind {
    LOGIN,
    CONSENT,
    LOGOUT
}
