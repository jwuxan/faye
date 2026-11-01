package faye.engine.testing;

import faye.engine.spi.CodeOutbox;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A {@link CodeOutbox} that keeps what it was given, for tests: every recorded send, delivered or
 * not, in order. It refuses once {@link #limit} sends were recorded for one limit key.
 */
public final class InMemoryOutbox implements CodeOutbox {

    /** One recorded send; {@code destination} is null for a row that is never delivered. */
    public record Sent(
            String tenant, Channel channel, @Nullable String destination, String limitKey, String code) {}

    private final List<Sent> sent = new ArrayList<>();
    private int limit = Integer.MAX_VALUE;

    /** Refuses a send once this many were recorded for its limit key. */
    public void limit(int limit) {
        this.limit = limit;
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    /** The code of the last send recorded. */
    public String lastCode() {
        return sent.getLast().code();
    }

    @Override
    public boolean enqueue(String tenant, Channel channel, @Nullable String destination, String limitKey, String code) {
        if (sent.stream().filter(s -> s.limitKey().equals(limitKey)).count() >= limit) return false;
        sent.add(new Sent(tenant, channel, destination, limitKey, code));
        return true;
    }
}
