package faye.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import faye.engine.TraceEvent;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@link TraceStore} in Redis. Each entry is its number, a space, and its JSON. {@code
 * preview:{tenant}:seq} numbers a tenant's events; {@code preview:{tenant}:feed} keeps the latest
 * {@value #FEED} of them; {@code trace:{flowId}} keeps a flow's events as long as the flow lives.
 */
final class RedisTraceStore implements TraceStore {

    /** Events kept in a tenant's feed; followers read it four times a second. */
    static final int FEED = 200;

    /** Entries a newer server wrote may carry fields this one does not know. */
    private static final ObjectMapper JSON =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final RedisScript<Long> APPEND = RedisScript.of("""
            local seq = redis.call('INCR', KEYS[1])
            local entry = seq .. ' ' .. ARGV[1]
            redis.call('RPUSH', KEYS[2], entry)
            redis.call('LTRIM', KEYS[2], -tonumber(ARGV[3]), -1)
            redis.call('RPUSH', KEYS[3], entry)
            redis.call('PEXPIRE', KEYS[3], ARGV[2])
            return seq
            """, Long.class);

    /** An entry without its number, which Redis assigns. */
    private record Entry(String tenantId, String flowId, long at, TraceEvent event) {}

    private final StringRedisTemplate redis;
    private final Duration ttl;

    /** @param ttl how long a flow's trace is kept after its last event */
    RedisTraceStore(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public void append(String tenantId, String flowId, TraceEvent event) {
        String json;
        try {
            json = JSON.writeValueAsString(new Entry(tenantId, flowId, System.currentTimeMillis(), event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        redis.execute(
                APPEND,
                List.of(seqKey(tenantId), feedKey(tenantId), "trace:" + flowId),
                json,
                Long.toString(ttl.toMillis()),
                Integer.toString(FEED));
    }

    @Override
    public List<Recorded> flow(String flowId) {
        return read(redis.opsForList().range("trace:" + flowId, 0, -1));
    }

    @Override
    public List<Recorded> feed(String tenantId, long after) {
        return read(redis.opsForList().range(feedKey(tenantId), 0, -1)).stream()
                .filter(r -> r.seq() > after)
                .toList();
    }

    @Override
    public long latest(String tenantId) {
        String seq = redis.opsForValue().get(seqKey(tenantId));
        return seq == null ? 0 : Long.parseLong(seq);
    }

    /** Entries in order; one that cannot be read is left out rather than hiding the rest. */
    private static List<Recorded> read(@Nullable List<String> entries) {
        return Objects.requireNonNullElse(entries, List.<String>of()).stream()
                .flatMap(e -> parse(e).stream())
                .toList();
    }

    private static Optional<Recorded> parse(String entry) {
        int space = entry.indexOf(' ');
        try {
            Entry e = JSON.readValue(entry.substring(space + 1), Entry.class);
            return Optional.of(new Recorded(
                    Long.parseLong(entry.substring(0, space)), e.tenantId(), e.flowId(), e.at(), e.event()));
        } catch (JsonProcessingException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String seqKey(String tenantId) {
        return "preview:" + tenantId + ":seq";
    }

    private static String feedKey(String tenantId) {
        return "preview:" + tenantId + ":feed";
    }
}
