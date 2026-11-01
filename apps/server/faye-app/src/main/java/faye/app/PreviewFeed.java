package faye.app;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Streams each tenant's preview trace feed to whoever follows it, as server sent events named
 * {@code trace} whose id is the event's number. One thread polls the {@link TraceStore} for every
 * follower, so events reach them whichever server ran the flow.
 */
@Component
final class PreviewFeed {

    static final Duration POLL = Duration.ofMillis(250);
    /** A follower's stream ends after this; the browser's EventSource reconnects from its last id. */
    static final Duration TIMEOUT = Duration.ofMinutes(30);
    /** Polls between comments that keep idle streams open through proxies: 15 s. */
    private static final long HEARTBEAT = 60;

    private record Follower(String tenantId, SseEmitter emitter, AtomicLong cursor) {}

    private final TraceStore traces;
    private final Set<Follower> followers = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "preview-feed");
        t.setDaemon(true);
        return t;
    });
    private long polls;

    PreviewFeed(TraceStore traces) {
        this.traces = traces;
        poller.scheduleWithFixedDelay(this::poll, POLL.toMillis(), POLL.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** @param lastEventId the last event the follower saw; null for only events from now on */
    SseEmitter follow(String tenantId, @Nullable String lastEventId) {
        long latest = traces.latest(tenantId);
        long cursor;
        try {
            // Past the latest (the store was emptied): from now on
            cursor = lastEventId == null ? latest : Math.min(Long.parseLong(lastEventId), latest);
        } catch (NumberFormatException e) {
            cursor = latest;
        }
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        Follower follower = new Follower(tenantId, emitter, new AtomicLong(cursor));
        emitter.onCompletion(() -> followers.remove(follower));
        emitter.onTimeout(() -> followers.remove(follower));
        emitter.onError(e -> followers.remove(follower));
        try {
            // Opens the stream at once, so the browser knows it is following
            emitter.send(SseEmitter.event().comment("following " + tenantId));
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }
        followers.add(follower);
        return emitter;
    }

    /**
     * Sends each follower its tenant's new events. A failed send drops the follower and ends its
     * stream, so its browser reconnects; a failed read is retried on the next poll. Nothing may
     * escape, or the schedule would stop.
     */
    private void poll() {
        boolean heartbeat = ++polls % HEARTBEAT == 0;
        for (Follower f : followers) {
            List<TraceStore.Recorded> fresh;
            try {
                fresh = traces.feed(f.tenantId(), f.cursor().get());
            } catch (RuntimeException e) {
                continue;
            }
            try {
                for (TraceStore.Recorded r : fresh) {
                    f.emitter()
                            .send(SseEmitter.event()
                                    .id(Long.toString(r.seq()))
                                    .name("trace")
                                    .data(r, MediaType.APPLICATION_JSON));
                    f.cursor().set(r.seq());
                }
                if (heartbeat) f.emitter().send(SseEmitter.event().comment(""));
            } catch (IOException | RuntimeException e) {
                followers.remove(f);
                try {
                    f.emitter().completeWithError(e);
                } catch (RuntimeException ended) {
                    // Already completed
                }
            }
        }
    }

    /** Ends every stream before the server's graceful shutdown would wait on them. */
    @EventListener(ContextClosedEvent.class)
    void close() {
        poller.shutdownNow();
        followers.forEach(f -> f.emitter().complete());
    }
}
