package faye.ir;

import java.util.List;
import java.util.Locale;

/**
 * A condition as data. Paths name a value in the flow environment ({@code user.has_totp});
 * comparisons always take a path on the left and a literal on the right.
 */
public sealed interface Predicate {

    /** Truthiness of the value at the path. */
    record Path(String path) implements Predicate {}

    record And(List<Predicate> all) implements Predicate {
        public And {
            all = List.copyOf(all);
        }
    }

    record Or(List<Predicate> any) implements Predicate {
        public Or {
            any = List.copyOf(any);
        }
    }

    record Not(Predicate operand) implements Predicate {}

    record Exists(String path) implements Predicate {}

    /** @param value a string, integer or boolean literal */
    record Compare(Op op, String path, Object value) implements Predicate {}

    enum Op {
        EQ,
        NE,
        GT,
        LT,
        /** the value at the path is a list containing the literal */
        HAS;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
