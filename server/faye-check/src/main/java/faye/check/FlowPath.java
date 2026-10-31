package faye.check;

import faye.engine.Acr;
import faye.engine.Env;
import faye.ir.Predicate;
import faye.ir.SourceRef;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One way through a section, as the checker enumerates it: the decisions taken in order, then how
 * the section ends. A retry whose body fails repeats that body's events once per attempt.
 */
public record FlowPath(String section, List<Event> events, End end) {

    /** Failure reason of a path where a handler failed; scripted fakes fail with this code. */
    public static final String HANDLER_ERROR = "handler_error";

    /** Failure reason of a path where a hook failed; scripted hooks fail with this code. */
    public static final String HOOK_ERROR = "hook_error";

    public FlowPath {
        events = List.copyOf(events);
    }

    public sealed interface Event {
        SourceRef source();
    }

    public record Entered(String step, SourceRef source) implements Event {}

    /**
     * A predicate the checker could not decide, taken as {@code holds} on this path.
     *
     * @param known what the checker knew there: everything but the paths it could not know
     */
    public record Assumed(Predicate test, boolean holds, Env known, SourceRef source) implements Event {}

    public record Chose(String option, SourceRef source) implements Event {}

    /**
     * @param amr what an ok call proved, sorted; empty for a handler that is not a credential; null
     *     when the call failed
     * @param bindsSubject the handler binds the subject
     */
    public record Called(String handler, @Nullable List<String> amr, boolean bindsSubject, SourceRef source)
            implements Event {
        public Called {
            amr = amr == null ? null : List.copyOf(amr);
        }

        public boolean ok() {
            return amr != null;
        }
    }

    /**
     * A hook ran. An ok hook in a step may have set any {@code vars}; an ok hook of a choose picked
     * the option this path takes.
     *
     * @param pick the option an ok hook of a choose picked; null for a hook in a step
     */
    public record Hooked(String hook, boolean ok, @Nullable String pick, SourceRef source) implements Event {}

    public sealed interface End {
        SourceRef source();
    }

    public record Succeeded(SourceRef source) implements End {}

    public record Failed(String reason, SourceRef source) implements End {}

    public boolean succeeded() {
        return end instanceof Succeeded;
    }

    /** The amr the engine computes for this path: values in the order first proven. */
    public List<String> amr() {
        Set<String> out = new LinkedHashSet<>();
        for (Event e : events) {
            if (e instanceof Called c && c.amr() != null) out.addAll(c.amr());
        }
        return List.copyOf(out);
    }

    public @Nullable String acr() {
        return Acr.of(amr());
    }

    /** The last place this path went one way rather than another, else where it ended. */
    public SourceRef decidedAt() {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i) instanceof Assumed || events.get(i) instanceof Chose) {
                return events.get(i).source();
            }
        }
        return end.source();
    }

    /** e.g. {@code identify > credential/password > second-factor (if at 22 false)} */
    public String describe() {
        List<String> parts = new ArrayList<>();
        for (Event e : events) {
            switch (e) {
                case Entered s -> parts.add(s.step());
                case Chose c -> parts.add((parts.isEmpty() ? "" : parts.removeLast()) + "/" + c.option());
                case Assumed a -> {
                    if (!a.holds()) {
                        parts.add(a.source().line() > 0 ? "(if at " + a.source().line() + " false)" : "(if false)");
                    }
                }
                case Called c -> {}
                case Hooked h -> {}
            }
        }
        String how = switch (end) {
            case Succeeded s -> "succeed " + (amr().isEmpty() ? "with no credential" : String.join("+", amr()));
            case Failed f -> "fail " + f.reason();
        };
        parts.add(how);
        return String.join(" > ", parts);
    }
}
