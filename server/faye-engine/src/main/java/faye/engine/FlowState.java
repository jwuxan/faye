package faye.engine;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A suspended flow: plain data, persisted between requests.
 *
 * @param versionHash flow version pinned at creation; never changes
 * @param hostRef challenge id held for the FlowHost
 * @param stack continuation, top of stack last
 * @param awaiting the screen the flow is waiting on; null once the flow has finished
 * @param revision bumped on every save, for optimistic concurrency
 */
public record FlowState(
        String flowId,
        String tenantId,
        String versionHash,
        HostKind kind,
        String hostRef,
        List<Frame> stack,
        Env env,
        @Nullable Awaiting awaiting,
        long revision) {

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
    }

    public String section() {
        return kind.section();
    }
}
