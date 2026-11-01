package faye.engine.spi;

import faye.engine.EntrySelector;

/** The kinds of challenge a host hands Faye. {@link EntrySelector} maps each to an IR entry. */
public enum HostKind {
    LOGIN,
    CONSENT,
    LOGOUT,
    /** A sign in on a second device that approves one waiting on another (QR code sign in, ADR 0032). */
    APPROVE
}
