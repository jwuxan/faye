package faye.app;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drains the code outbox once a second on its own thread, so a slow provider never slows a screen
 * (ADR 0032). Several Faye processes on one database may each drain; a code is then at worst sent
 * twice, never lost.
 */
final class OutboxWorker implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(OutboxWorker.class.getName());

    private final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "faye-outbox");
        t.setDaemon(true);
        return t;
    });

    OutboxWorker(SqliteCodeOutbox outbox) {
        thread.scheduleWithFixedDelay(
                () -> {
                    try {
                        outbox.drain();
                    } catch (RuntimeException e) {
                        LOG.log(
                                System.Logger.Level.WARNING,
                                "outbox drain failed: {0}",
                                e.getClass().getSimpleName());
                    }
                },
                1,
                1,
                TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        thread.shutdownNow();
    }
}
