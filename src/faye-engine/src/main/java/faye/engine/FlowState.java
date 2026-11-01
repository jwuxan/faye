package faye.engine;

import faye.engine.spi.HostKind;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A suspended flow: plain data, persisted between requests.
 *
 * @param versionHash flow version pinned at creation; never changes
 * @param kind the host challenge the flow answers
 * @param entry id of the IR entry the flow runs, chosen by {@link EntrySelector} at creation
 * @param hostRef challenge id held for the FlowHost
 * @param binder hex SHA-256 of the secret the browser that started the flow holds
 * @param stack continuation, top of stack last
 * @param awaiting the screen the flow is waiting on; null once the flow has finished
 * @param pending writes the entry staged so far, applied only if it succeeds; empty once it ends
 * @param revision bumped on every save, for optimistic concurrency
 * @param preview the host started the flow for a preview client; absent in states saved before
 *     the field existed, which read as false
 * @param proven for a consent, what the login that proved its subject learned about the person,
 *     taken when the consent started; the host receives exactly this. Empty otherwise, and in states
 *     saved before the field existed
 * @param remember the person asked to stay signed in, on a screen that offered it (ADR 0033); the
 *     host is told when the sign in succeeds. Absent in states saved before the field existed,
 *     which read as false
 * @param expiry when the flow stops being answerable, from its tenant's flow settings when it
 *     started (ADR 0035); null when they set no limit, and in states saved before the field existed:
 *     then only the store's retention ends the flow
 */
public record FlowState(
        String flowId,
        String tenantId,
        String versionHash,
        HostKind kind,
        String entry,
        String hostRef,
        String binder,
        List<Frame> stack,
        Env env,
        @Nullable Awaiting awaiting,
        List<Effect> pending,
        long revision,
        boolean preview,
        Map<String, Object> proven,
        boolean remember,
        @Nullable Expiry expiry) {

    /**
     * The limits an entry runs under (ADR 0035), in epoch milliseconds of the engine's clock.
     *
     * @param deadline when the entry can no longer be answered; null for no total limit
     * @param idleMillis how long after {@code touched} it can still be answered; null for no idle limit
     * @param touched when the entry started, or the engine last took an answer
     */
    public record Expiry(@Nullable Long deadline, @Nullable Long idleMillis, long touched) {

        /** Whether the entry can no longer be answered at {@code now}. */
        public boolean passed(long now) {
            return (deadline != null && now >= deadline) || (idleMillis != null && now - touched >= idleMillis);
        }

        Expiry touch(long now) {
            return new Expiry(deadline, idleMillis, now);
        }
    }

    /**
     * The flow waits on a screen.
     *
     * @param step innermost enclosing step id, or null
     * @param data server issued data for this screen (challenge, secret, options); handed to the
     *     handlers that consume the answer as {@link Submission#context()}
     * @param error code of the failure that sent the flow back to this screen, or null
     * @param attemptsLeft attempts the enclosing retry has left after that failure, or null
     */
    public record Awaiting(
            List<Integer> path,
            String screen,
            @Nullable String step,
            String nonce,
            Map<String, Object> data,
            @Nullable String error,
            @Nullable Integer attemptsLeft) {
        public Awaiting {
            Objects.requireNonNull(path);
            Objects.requireNonNull(screen);
            Objects.requireNonNull(nonce);
            path = List.copyOf(path);
            data = Map.copyOf(data);
        }
    }

    /**
     * Every component but the nullable ones and those that default ({@code preview}, {@code proven},
     * {@code remember}, absent in older states; {@code expiry} is nullable) is required, so a state
     * read with one missing fails to read.
     */
    public FlowState {
        Objects.requireNonNull(flowId);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(versionHash);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(entry);
        Objects.requireNonNull(hostRef);
        Objects.requireNonNull(binder);
        Objects.requireNonNull(env);
        stack = List.copyOf(stack);
        pending = List.copyOf(pending);
        // Absent in states saved before the field existed
        proven = proven == null ? Map.of() : Map.copyOf(proven);
    }

    /** This state, with the person's answer to "keep me signed in". */
    FlowState withRemember(boolean remember) {
        return new FlowState(
                flowId,
                tenantId,
                versionHash,
                kind,
                entry,
                hostRef,
                binder,
                stack,
                env,
                awaiting,
                pending,
                revision,
                preview,
                proven,
                remember,
                expiry);
    }

    /**
     * This state, still waiting on the same screen with {@code data} after an action (ADR 0032), as
     * the next revision.
     */
    FlowState acted(Map<String, Object> data, @Nullable String error) {
        Awaiting a = Objects.requireNonNull(awaiting, "only a waiting flow takes an action");
        return new FlowState(
                flowId,
                tenantId,
                versionHash,
                kind,
                entry,
                hostRef,
                binder,
                stack,
                env,
                new Awaiting(a.path(), a.screen(), a.step(), a.nonce(), data, error, a.attemptsLeft()),
                pending,
                revision + 1,
                preview,
                proven,
                remember,
                expiry);
    }

    /** This state, with an answer taken at {@code now}: the idle timeout counts from it. */
    FlowState touched(long now) {
        if (expiry == null) return this;
        return new FlowState(
                flowId,
                tenantId,
                versionHash,
                kind,
                entry,
                hostRef,
                binder,
                stack,
                env,
                awaiting,
                pending,
                revision,
                preview,
                proven,
                remember,
                expiry.touch(now));
    }
}
