package faye.engine;

import faye.ir.Predicate;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Evaluates IR predicates against an {@link Env}. A missing path is null: falsy, equal to nothing. */
public final class Predicates {

    private Predicates() {}

    public static boolean test(Predicate predicate, Env env) {
        return switch (predicate) {
            case Predicate.Path p -> truthy(env.lookup(p.path()));
            case Predicate.And a -> a.all().stream().allMatch(p -> test(p, env));
            case Predicate.Or o -> o.any().stream().anyMatch(p -> test(p, env));
            case Predicate.Not n -> !test(n.operand(), env);
            case Predicate.Exists e -> env.lookup(e.path()) != null;
            case Predicate.Compare c -> {
                Object value = env.lookup(c.path());
                yield switch (c.op()) {
                    case EQ -> equal(value, c.value());
                    case NE -> !equal(value, c.value());
                    case GT ->
                        integer(value) instanceof BigInteger v
                                && integer(c.value()) instanceof BigInteger l
                                && v.compareTo(l) > 0;
                    case LT ->
                        integer(value) instanceof BigInteger v
                                && integer(c.value()) instanceof BigInteger l
                                && v.compareTo(l) < 0;
                    case HAS ->
                        value instanceof Collection<?> list && list.stream().anyMatch(v -> equal(v, c.value()));
                };
            }
        };
    }

    static boolean truthy(@Nullable Object value) {
        return switch (value) {
            case null -> false;
            case Boolean b -> b;
            case String s -> !s.isEmpty();
            case Number n -> !zero(n);
            case Collection<?> c -> !c.isEmpty();
            case Map<?, ?> m -> !m.isEmpty();
            default -> true;
        };
    }

    /**
     * Integers compare by value whatever their Java type; any other value, a fractional number
     * included, only equals an equal value of its own type. Values from the host (client metadata,
     * settings) can be fractional or wider than 64 bits, and {@code longValue()} would truncate or
     * wrap them (ADR 0007, seed finding 2).
     */
    private static boolean equal(@Nullable Object a, @Nullable Object b) {
        if (integer(a) instanceof BigInteger x && integer(b) instanceof BigInteger y) return x.equals(y);
        return Objects.equals(a, b);
    }

    /** The value as an exact integer, or null if it is not an integer type (ir-spec.md, Predicates). */
    private static @Nullable BigInteger integer(@Nullable Object value) {
        return switch (value) {
            case Integer i -> BigInteger.valueOf(i);
            case Long l -> BigInteger.valueOf(l);
            case Short s -> BigInteger.valueOf(s);
            case Byte b -> BigInteger.valueOf(b);
            case BigInteger i -> i;
            case null, default -> null;
        };
    }

    private static boolean zero(Number n) {
        return switch (n) {
            case Double d -> d == 0;
            case Float f -> f == 0;
            case BigDecimal d -> d.signum() == 0;
            default -> integer(n) instanceof BigInteger i ? i.signum() == 0 : n.doubleValue() == 0;
        };
    }
}
