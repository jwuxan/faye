package faye.engine;

import faye.ir.Entry;
import faye.ir.Flow;
import java.util.Optional;

/**
 * Which entry of a flow runs for a host challenge: a login runs the flow's {@code authenticate}
 * entry, a consent its {@code consent} entry. The checker refuses a flow with more than one entry
 * of a kind, so the choice is never ambiguous; how to select among several is left to the ADR that
 * first needs it.
 */
public final class EntrySelector {

    private EntrySelector() {}

    /** The kind of entry a challenge runs; empty for a challenge that runs none (logout). */
    public static Optional<Entry.Kind> kind(HostKind host) {
        return switch (host) {
            case LOGIN -> Optional.of(Entry.Kind.AUTHENTICATE);
            case CONSENT -> Optional.of(Entry.Kind.CONSENT);
            case LOGOUT -> Optional.empty();
        };
    }

    /** The id of the entry this flow runs for the challenge, if it has exactly one of that kind. */
    public static Optional<String> select(Flow flow, HostKind host) {
        return kind(host).flatMap(flow::onlyEntry);
    }
}
