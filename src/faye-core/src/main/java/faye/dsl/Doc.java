package faye.dsl;

import faye.ir.SourceRef;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A document as a format adapter reads it: mappings, sequences and scalars, each with the position
 * it came from. Lowering reads only this, so a second surface format needs only a second reader.
 */
public sealed interface Doc {

    SourceRef source();

    /**
     * A YAML mapping.
     *
     * @param entries in document order, keyed by the key's text; duplicate keys were refused by the
     *     reader
     */
    record Map(java.util.Map<String, Entry> entries, SourceRef source) implements Doc {
        public Map {
            entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        }
    }

    /** One key and its value; {@code key} is where the key was written. */
    record Entry(SourceRef key, Doc value) {}

    record Seq(List<Doc> items, SourceRef source) implements Doc {
        public Seq {
            items = List.copyOf(items);
        }
    }

    /**
     * A YAML scalar.
     *
     * @param value the scalar as the format typed it: a String, Integer, Long, Boolean or Double, or
     *     null for a null
     */
    record Scalar(@Nullable Object value, SourceRef source) implements Doc {}
}
