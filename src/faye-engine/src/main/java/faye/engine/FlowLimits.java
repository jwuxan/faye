package faye.engine;

import faye.ir.Call;
import faye.ir.Choose;
import faye.ir.Fail;
import faye.ir.Flow;
import faye.ir.Hook;
import faye.ir.Node;
import faye.ir.Predicate;
import faye.ir.Retry;
import faye.ir.Screen;
import faye.ir.Seq;
import faye.ir.SetVar;
import faye.ir.SourceRef;
import faye.ir.Step;
import faye.ir.Succeed;
import faye.ir.When;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The hard bounds every flow stays under, checked on the IR so they hold for every frontend. The
 * static limits run as part of {@link Verifier} (L1): at publish, at preview and whenever the
 * engine runs a version, so a stored version that crosses one fails closed like a catalog that
 * lost a handler. The hook budgets also feed the checker's L2 rules, and {@link Engine} keeps the
 * per run backstops.
 */
public final class FlowLimits {

    /** IR nodes in a flow (every form, across entries). */
    public static final int MAX_NODES = 1000;

    /** {@code step}s in a flow. */
    public static final int MAX_STEPS = 64;

    /** Hook attachments in a flow ({@code hook} nodes plus {@code choose} pick hooks). */
    public static final int MAX_HOOK_ATTACHMENTS = 16;

    /** Distinct hooks (hashes) a flow pins. */
    public static final int MAX_HOOKS = 8;

    /** Options of one {@code choose}. */
    public static final int MAX_OPTIONS = 8;

    /** Product of the attempts of nested {@code retry}s on any path. */
    public static final int MAX_RETRY_PRODUCT = 10;

    /** Distinct names written by {@code set} in a flow. */
    public static final int MAX_SET_NAMES = 32;

    /** Length of a string literal ({@code set} values, predicate literals), in characters. */
    public static final int MAX_LITERAL = 1024;

    /** Length of an id, key, handler name or {@code fail} reason, in characters. */
    public static final int MAX_NAME = 64;

    /** Hook runs in an entry: the sum of each attachment's retry product (L2). */
    public static final int MAX_HOOK_RUNS = 32;

    /** Hook time in an entry: the same sum weighted by each hook's timeout, in milliseconds (L2). */
    public static final int MAX_HOOK_MILLIS = 5000;

    /** Hook runs in one run; the next hook fails with {@code hook_error} (runtime backstop). */
    public static final int RUN_HOOK_RUNS = 32;

    /**
     * Measured hook time in one run, in milliseconds; twice the static bound, to leave room for
     * queueing (runtime backstop).
     */
    public static final int RUN_HOOK_MILLIS = 10_000;

    /** Distinct {@code vars} names; a hook {@code set} that would pass it fails (runtime backstop). */
    public static final int MAX_VARS = 64;

    /** Encoded flow state when the flow suspends, in bytes (runtime backstop). */
    public static final int MAX_STATE_BYTES = 256 * 1024;

    private FlowLimits() {}

    /**
     * A hook attachment and how many times one pass of its entry may run it: the product of the
     * attempts of the {@code retry}s around it.
     */
    public record HookLoad(Hook hook, int runs) {}

    /**
     * Every static limit a flow crosses, one finding each, pointing at the node that crosses it:
     * the first node past a flow wide count, the innermost {@code retry} that pushes a path's
     * product over, and the node that holds an overlong literal or name.
     */
    public static List<Verifier.Problem> check(Flow flow) {
        Counts counts = new Counts();
        List<Verifier.Problem> out = new ArrayList<>();
        flow.entries().forEach((id, entry) -> {
            name(id, "entry id", entry.body().source(), out);
            walk(entry.body(), 1, counts, out);
        });
        return out;
    }

    /** The hook attachments of one entry's body, each with the retry product around it. */
    public static List<HookLoad> hookLoads(Node body) {
        List<HookLoad> out = new ArrayList<>();
        loads(body, 1, out);
        return out;
    }

    private static final class Counts {
        int nodes;
        int steps;
        int attachments;
        boolean nodesHit;
        boolean stepsHit;
        boolean attachmentsHit;
        boolean hashesHit;
        boolean setNamesHit;
        final Set<String> hashes = new HashSet<>();
        final Set<String> setNames = new HashSet<>();
    }

    private static void walk(Node node, long product, Counts counts, List<Verifier.Problem> out) {
        if (++counts.nodes > MAX_NODES && !counts.nodesHit) {
            counts.nodesHit = true;
            out.add(new Verifier.Problem(node.source(), "a flow has at most " + MAX_NODES + " nodes"));
        }
        switch (node) {
            case Seq s -> s.children().forEach(c -> walk(c, product, counts, out));
            case Step s -> {
                name(s.id(), "step id", s.source(), out);
                if (++counts.steps > MAX_STEPS && !counts.stepsHit) {
                    counts.stepsHit = true;
                    out.add(new Verifier.Problem(s.source(), "a flow has at most " + MAX_STEPS + " steps"));
                }
                walk(s.body(), product, counts, out);
            }
            case When w -> {
                literal(w.test(), w.source(), out);
                walk(w.body(), product, counts, out);
            }
            case Retry r -> {
                long next = product * r.attempts();
                if (product <= MAX_RETRY_PRODUCT && next > MAX_RETRY_PRODUCT) {
                    out.add(new Verifier.Problem(
                            r.source(),
                            "retries on one path multiply to more than " + MAX_RETRY_PRODUCT + " attempts"));
                }
                walk(r.body(), Math.min(next, Integer.MAX_VALUE), counts, out);
            }
            case Choose c -> {
                if (c.options().size() > MAX_OPTIONS) {
                    out.add(new Verifier.Problem(c.source(), "a choose has at most " + MAX_OPTIONS + " options"));
                }
                if (c.pick() != null) attachment(c.pick(), counts, out);
                for (Choose.Opt o : c.options()) {
                    name(o.id(), "option id", o.source(), out);
                    if (o.guard() != null) literal(o.guard(), o.guardSource(), out);
                    walk(o.body(), product, counts, out);
                }
            }
            case Screen s -> {}
            case Call c -> name(c.handler(), "handler name", c.source(), out);
            case Hook h -> attachment(h, counts, out);
            case SetVar s -> {
                name(s.name(), "set name", s.source(), out);
                if (s.value() instanceof String literal && literal.length() > MAX_LITERAL) {
                    out.add(new Verifier.Problem(s.source(), "a literal is at most " + MAX_LITERAL + " characters"));
                }
                if (counts.setNames.add(s.name()) && counts.setNames.size() > MAX_SET_NAMES && !counts.setNamesHit) {
                    counts.setNamesHit = true;
                    out.add(new Verifier.Problem(
                            s.source(), "a flow writes at most " + MAX_SET_NAMES + " distinct names"));
                }
            }
            case Succeed s -> {}
            case Fail f -> name(f.reason(), "fail reason", f.source(), out);
        }
    }

    private static void attachment(Hook hook, Counts counts, List<Verifier.Problem> out) {
        name(hook.id(), "hook id", hook.source(), out);
        if (++counts.attachments > MAX_HOOK_ATTACHMENTS && !counts.attachmentsHit) {
            counts.attachmentsHit = true;
            out.add(new Verifier.Problem(
                    hook.source(), "a flow has at most " + MAX_HOOK_ATTACHMENTS + " hook attachments"));
        }
        if (counts.hashes.add(hook.hash()) && counts.hashes.size() > MAX_HOOKS && !counts.hashesHit) {
            counts.hashesHit = true;
            out.add(new Verifier.Problem(hook.source(), "a flow pins at most " + MAX_HOOKS + " distinct hooks"));
        }
    }

    private static void literal(Predicate predicate, SourceRef source, List<Verifier.Problem> out) {
        switch (predicate) {
            case Predicate.And a -> a.all().forEach(q -> literal(q, source, out));
            case Predicate.Or o -> o.any().forEach(q -> literal(q, source, out));
            case Predicate.Not n -> literal(n.operand(), source, out);
            case Predicate.Path a -> {}
            case Predicate.Exists a -> {}
            case Predicate.Compare c -> {
                if (c.value() instanceof String literal && literal.length() > MAX_LITERAL) {
                    out.add(new Verifier.Problem(source, "a literal is at most " + MAX_LITERAL + " characters"));
                }
            }
        }
    }

    private static void name(String name, String what, SourceRef source, List<Verifier.Problem> out) {
        if (name.length() > MAX_NAME) {
            out.add(new Verifier.Problem(source, "a " + what + " is at most " + MAX_NAME + " characters"));
        }
    }

    private static void loads(Node node, long product, List<HookLoad> out) {
        switch (node) {
            case Seq s -> s.children().forEach(c -> loads(c, product, out));
            case Step s -> loads(s.body(), product, out);
            case When w -> loads(w.body(), product, out);
            case Retry r -> loads(r.body(), Math.min(product * r.attempts(), Integer.MAX_VALUE), out);
            case Choose c -> {
                if (c.pick() != null) out.add(new HookLoad(c.pick(), (int) product));
                c.options().forEach(o -> loads(o.body(), product, out));
            }
            case Hook h -> out.add(new HookLoad(h, (int) product));
            case Screen s -> {}
            case Call c -> {}
            case SetVar s -> {}
            case Succeed s -> {}
            case Fail f -> {}
        }
    }
}
