package faye.engine;

import faye.ir.Predicate;
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
                    case GT -> value instanceof Number n && n.longValue() > ((Number) c.value()).longValue();
                    case LT -> value instanceof Number n && n.longValue() < ((Number) c.value()).longValue();
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
            case Number n -> n.longValue() != 0;
            case Collection<?> c -> !c.isEmpty();
            case Map<?, ?> m -> !m.isEmpty();
            default -> true;
        };
    }

    /** Integers compare by value whatever their Java type. */
    private static boolean equal(@Nullable Object a, @Nullable Object b) {
        if (a instanceof Number x && b instanceof Number y) return x.longValue() == y.longValue();
        return Objects.equals(a, b);
    }
}
