package faye.ir;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A whole flow in the current IR format: its entries by id, in declaration order. A reader of an
 * older supported format upgrades it to this one, so nothing downstream sees a format number.
 *
 * @param version the author's version of the flow, not the IR format
 */
public record Flow(String id, int version, Map<String, Entry> entries) {

    public Flow {
        entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /** Ids of the entries of this kind, in declaration order. */
    public java.util.List<String> entriesOf(Entry.Kind kind) {
        return entries.entrySet().stream()
                .filter(e -> e.getValue().kind() == kind)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** The one entry of this kind; empty if there is none or more than one. */
    public Optional<String> onlyEntry(Entry.Kind kind) {
        var ids = entriesOf(kind);
        return ids.size() == 1 ? Optional.of(ids.get(0)) : Optional.empty();
    }
}
