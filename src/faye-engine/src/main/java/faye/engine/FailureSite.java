package faye.engine;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Where a run ended abnormally, as the engine knows it: the node, the innermost step around it, and
 * the code it ended with, plus a reference unique to this failure that whoever reports it can hand
 * the person signing in, so support can find the operator's log line (ADR 0027). Paths only: the
 * engine never sees a file position.
 *
 * @param path the node that failed: the {@code fail}, the {@code call}, {@code hook} or screen
 *     whose failure no retry caught, the {@code succeed} whose checks or writes failed; empty for an
 *     entry that ran out of nodes ({@code incomplete})
 * @param step the innermost step around that node, or null
 * @param code the reason the entry ended, or {@code host_error} when the host could not be told
 * @param reference {@code ref_} and 12 letters and digits, random
 */
public record FailureSite(
        String entry, List<Integer> path, @Nullable String step, String code, String reference)
        implements java.io.Serializable {

    /** What a {@link #reference} looks like. */
    public static final Pattern REFERENCE = Pattern.compile("ref_[A-Za-z0-9]{12}");

    public FailureSite {
        Objects.requireNonNull(entry);
        Objects.requireNonNull(code);
        Objects.requireNonNull(reference);
        path = List.copyOf(path);
    }

    public Site site() {
        return new Site(entry, path);
    }
}
