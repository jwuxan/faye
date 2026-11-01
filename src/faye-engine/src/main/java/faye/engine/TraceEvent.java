package faye.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One thing a preview flow did, for its live trace. Events carry ids and codes, never what the
 * person typed.
 *
 * @param event {@code start} ({@code id} the version hash, {@code outcome} the entry id), {@code
 *     step}, {@code option} ({@code outcome} {@code only}, {@code user} or {@code hook}), {@code
 *     screen} ({@code outcome} {@code issuer_error} when its issuer failed), {@code call}
 *     ({@code outcome} {@code ok} or the error; {@code amr} what it proved),
 *     {@code hook} ({@code outcome} {@code ok} or the failure), {@code retry} ({@code outcome} the
 *     error being retried), {@code commit} ({@code id} the handler, {@code outcome} the code of an
 *     effect whose commit failed), {@code end} ({@code outcome} {@code succeed} or the reason;
 *     {@code amr} and {@code acr} of a login that succeeded)
 * @param at the node the event is about, on every event but {@code start}: the step, the option's
 *     body, the screen (the {@code choose} for {@code method_picker}), the call, the hook (the
 *     {@code choose} for its pick hook), the retry, and for {@code commit} and {@code end} the node
 *     the entry ended at ({@link FailureSite#path()})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TraceEvent(
        String event,
        @Nullable String id,
        @Nullable String outcome,
        @Nullable List<String> amr,
        @Nullable String acr,
        @Nullable Site at) {

    public TraceEvent {
        amr = amr == null ? null : List.copyOf(amr);
    }

    /** An event with no {@code at}, as events were before ADR 0027. */
    public TraceEvent(
            String event,
            @Nullable String id,
            @Nullable String outcome,
            @Nullable List<String> amr,
            @Nullable String acr) {
        this(event, id, outcome, amr, acr, null);
    }

    static TraceEvent of(String event, @Nullable String id, @Nullable String outcome) {
        return new TraceEvent(event, id, outcome, null, null, null);
    }

    static TraceEvent of(String event, @Nullable String id, @Nullable String outcome, Site at) {
        return new TraceEvent(event, id, outcome, null, null, at);
    }

    /**
     * The event as the conformance vectors write it: its fields that are set, space separated, amr
     * joined by {@code +}, and no version hash ({@code start login}, {@code call password.verify ok
     * pwd}, {@code end succeed pwd faye:1fa}). Leaves out {@code at}, which vectors check apart.
     */
    public String describe() {
        List<String> parts = new ArrayList<>(List.of(event));
        if (id != null && !"start".equals(event)) parts.add(id);
        if (outcome != null) parts.add(outcome);
        if (amr != null && !amr.isEmpty()) parts.add(String.join("+", amr));
        if (acr != null) parts.add(acr);
        return String.join(" ", parts);
    }
}
