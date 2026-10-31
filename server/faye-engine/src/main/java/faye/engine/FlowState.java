package faye.engine;

import java.util.List;
import java.util.Map;
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
        boolean preview) {

    /**
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
            data = Map.copyOf(data);
        }
    }

    public FlowState {
        stack = List.copyOf(stack);
        pending = List.copyOf(pending);
    }
}
