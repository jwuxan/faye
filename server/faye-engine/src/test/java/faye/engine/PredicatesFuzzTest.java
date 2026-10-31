package faye.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import faye.ir.Predicate;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * F3 and F4 (ADR 0007). Predicates: for any predicate and any environment, of any JSON type and
 * any number width, {@link Predicates#test} never throws (O1) and agrees with a reference evaluator
 * written from ir-spec.md, Predicates (O10). Acr: {@link Acr#of} and {@link Acr#satisfies} never
 * throw on any {@code acr_values} a host could relay, and are monotone: proving more never lowers
 * {@code acr}, and a stronger {@code acr} satisfies whatever a weaker one does.
 */
class PredicatesFuzzTest {

    static final List<String> PATHS =
            List.of("user.a", "user.b", "vars.a", "host.acr_values", "client.metadata.n", "auth.amr", "auth.acr");

    @FuzzTest(maxDuration = "60s")
    void predicates(FuzzedDataProvider data) {
        Map<String, Object> user = new LinkedHashMap<>();
        Map<String, Object> vars = new LinkedHashMap<>();
        Map<String, Object> host = new LinkedHashMap<>();
        Map<String, Object> metadata = new LinkedHashMap<>();
        put(user, "a", value(data, 2));
        put(user, "b", value(data, 2));
        put(vars, "a", value(data, 2));
        put(host, "acr_values", value(data, 2));
        put(metadata, "n", value(data, 2));
        Env env = new Env(
                Map.of(),
                Map.of("metadata", metadata),
                Map.of(),
                host,
                user,
                vars,
                new Env.Auth(data.consumeBoolean() ? "alice" : null, List.of()));
        Predicate p = predicate(data, 4);
        assertThat(Predicates.test(p, env)).as(p.toString()).isEqualTo(reference(p, env));
    }

    @FuzzTest(maxDuration = "60s")
    void acr(FuzzedDataProvider data) {
        List<String> amr = new ArrayList<>();
        int n = data.consumeInt(0, 4);
        for (int i = 0; i < n; i++) amr.add(data.pickValue(List.of("pwd", "otp", "hwk", "user", "x", "")));
        @Nullable Object requested = value(data, 2);
        @Nullable String acr = Acr.of(amr);
        assertThat(acr == null).isEqualTo(amr.isEmpty());
        // Proving one more amr never lowers acr
        List<String> more = new ArrayList<>(amr);
        more.add(data.pickValue(List.of("pwd", "otp", "hwk", "user", "x")));
        assertThat(rank(Acr.of(more))).isGreaterThanOrEqualTo(rank(acr));
        // A stronger acr satisfies whatever a weaker one does
        boolean one = Acr.satisfies(Acr.ONE_FACTOR, requested);
        boolean two = Acr.satisfies(Acr.TWO_FACTOR, requested);
        boolean none = Acr.satisfies(null, requested);
        if (none) assertThat(one).isTrue();
        if (one) assertThat(two).isTrue();
        // Asking for faye:2fa, as a list or as OIDC's string, is never met by one factor
        assertThat(Acr.satisfies(Acr.ONE_FACTOR, List.of(Acr.TWO_FACTOR))).isFalse();
        assertThat(Acr.satisfies(Acr.ONE_FACTOR, "x " + Acr.TWO_FACTOR)).isFalse();
        assertThat(Acr.satisfies(acr, requested)).isIn(true, false);
    }

    /** The cases seed finding 2 named, as a readable regression. */
    @Test
    void numbersCompareExactlyAndOnlyIntegersOrder() {
        Env env = new Env(
                Map.of(),
                Map.of("metadata", Map.of("big", new BigInteger("18446744073709551621"), "f", 1.9, "half", 0.5)),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Env.Auth.NONE);
        assertThat(Predicates.test(new Predicate.Compare(Predicate.Op.GT, "client.metadata.big", 5), env))
                .isTrue();
        assertThat(Predicates.test(new Predicate.Compare(Predicate.Op.EQ, "client.metadata.big", 5), env))
                .isFalse();
        assertThat(Predicates.test(new Predicate.Compare(Predicate.Op.GT, "client.metadata.f", 1), env))
                .isFalse();
        assertThat(Predicates.test(new Predicate.Compare(Predicate.Op.EQ, "client.metadata.f", 1), env))
                .isFalse();
        assertThat(Predicates.test(new Predicate.Path("client.metadata.half"), env))
                .isTrue();
    }

    /** F4 finding: a host relaying acr_values with a null or a number in it. */
    @Test
    void acrValuesOfAnyShapeAreRead() {
        List<@Nullable Object> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(Acr.TWO_FACTOR);
        assertThat(Acr.satisfies(Acr.ONE_FACTOR, withNull)).isFalse();
        assertThat(Acr.satisfies(Acr.TWO_FACTOR, withNull)).isTrue();
        assertThat(Acr.satisfies(Acr.ONE_FACTOR, List.of(7, Acr.ONE_FACTOR))).isTrue();
        assertThat(Acr.satisfies(Acr.ONE_FACTOR, " faye:2fa  ")).isFalse();
        assertThat(Acr.satisfies(null, 42)).isTrue();
    }

    // ---- the reference, from ir-spec.md, Predicates ----

    static boolean reference(Predicate p, Env env) {
        return switch (p) {
            case Predicate.Path x -> truthy(env.lookup(x.path()));
            case Predicate.And x -> {
                boolean all = true;
                for (Predicate q : x.all()) all &= reference(q, env);
                yield all;
            }
            case Predicate.Or x -> {
                boolean any = false;
                for (Predicate q : x.any()) any |= reference(q, env);
                yield any;
            }
            case Predicate.Not x -> !reference(x.operand(), env);
            case Predicate.Exists x -> env.lookup(x.path()) != null;
            case Predicate.Compare x -> {
                @Nullable Object v = env.lookup(x.path());
                yield switch (x.op()) {
                    case EQ -> same(v, x.value());
                    case NE -> !same(v, x.value());
                    case GT ->
                        isInteger(v) && isInteger(x.value()) && asDecimal(v).compareTo(asDecimal(x.value())) > 0;
                    case LT ->
                        isInteger(v) && isInteger(x.value()) && asDecimal(v).compareTo(asDecimal(x.value())) < 0;
                    case HAS -> v instanceof Collection<?> c && c.stream().anyMatch(e -> same(e, x.value()));
                };
            }
        };
    }

    /** false for null, false, empty string, zero, empty list or object. */
    static boolean truthy(@Nullable Object v) {
        if (v == null || Boolean.FALSE.equals(v) || "".equals(v)) return false;
        // NaN and the infinities are not zero
        if (v instanceof Double d && !Double.isFinite(d)) return true;
        if (v instanceof Number n) return new BigDecimal(n.toString()).signum() != 0;
        if (v instanceof Collection<?> c) return !c.isEmpty();
        if (v instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }

    static boolean same(@Nullable Object a, @Nullable Object b) {
        if (isInteger(a) && isInteger(b)) return asDecimal(a).compareTo(asDecimal(b)) == 0;
        return a == null ? b == null : a.equals(b);
    }

    static boolean isInteger(@Nullable Object v) {
        return v instanceof Integer
                || v instanceof Long
                || v instanceof Short
                || v instanceof Byte
                || v instanceof BigInteger;
    }

    static BigDecimal asDecimal(@Nullable Object v) {
        return new BigDecimal(String.valueOf(v));
    }

    static int rank(@Nullable String acr) {
        return acr == null ? 0 : Acr.TWO_FACTOR.equals(acr) ? 2 : 1;
    }

    // ---- generators ----

    static Predicate predicate(FuzzedDataProvider data, int depth) {
        int form = depth <= 0 ? data.consumeInt(0, 2) : data.consumeInt(0, 6);
        return switch (form) {
            case 0 -> new Predicate.Path(data.pickValue(PATHS));
            case 1 -> new Predicate.Exists(data.pickValue(PATHS));
            case 2 ->
                new Predicate.Compare(data.pickValue(Predicate.Op.values()), data.pickValue(PATHS), literal(data));
            case 3 -> new Predicate.Not(predicate(data, depth - 1));
            case 4 -> new Predicate.And(List.of(predicate(data, depth - 1), predicate(data, depth - 1)));
            case 5 -> new Predicate.Or(List.of(predicate(data, depth - 1), predicate(data, depth - 1)));
            default -> new Predicate.Compare(Predicate.Op.HAS, data.pickValue(PATHS), literal(data));
        };
    }

    /** An IR literal: a string, a 64 bit integer (as Integer when it fits, as the codec reads it) or a boolean. */
    static Object literal(FuzzedDataProvider data) {
        return switch (data.consumeInt(0, 3)) {
            case 0 -> data.consumeString(8);
            case 1 -> {
                long l = data.consumeLong();
                yield l == (int) l ? (Object) (int) l : l;
            }
            case 2 -> data.pickValue(List.of(0, 1, -1, 5, Acr.TWO_FACTOR, "pwd"));
            default -> data.consumeBoolean();
        };
    }

    /** Any value a host or handler could put in the environment. */
    static @Nullable Object value(FuzzedDataProvider data, int depth) {
        return switch (data.consumeInt(0, depth > 0 ? 11 : 9)) {
            case 0 -> null;
            case 1 -> data.consumeBoolean();
            case 2 -> data.consumeInt();
            case 3 -> data.consumeLong();
            case 4 -> new BigInteger(1, data.consumeBytes(12)).subtract(BigInteger.TWO.pow(40));
            case 5 -> data.consumeDouble();
            case 6 -> data.consumeString(12);
            case 7 -> data.pickValue(List.of(0, 0.0, -0.0, 1, 1L, 1.0, Double.NaN, Acr.TWO_FACTOR, "pwd", ""));
            case 8 -> new BigDecimal(data.consumeInt()).movePointLeft(data.consumeInt(0, 4));
            case 9 -> (short) data.consumeInt(-200, 200);
            case 10 -> {
                List<@Nullable Object> list = new ArrayList<>();
                int n = data.consumeInt(0, 3);
                for (int i = 0; i < n; i++) list.add(value(data, depth - 1));
                yield list;
            }
            default -> {
                Map<String, @Nullable Object> map = new LinkedHashMap<>();
                map.put("n", value(data, depth - 1));
                yield map;
            }
        };
    }

    /** Env maps hold no nulls; a missing value is a missing key. */
    static void put(Map<String, Object> map, String key, @Nullable Object value) {
        if (value != null) map.put(key, value);
    }
}
