package faye.app;

import faye.engine.FlowState;
import faye.engine.FlowStateCodec;
import faye.engine.FlowStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Flow state as a Redis hash {@code flow:{flowId}} of {@code rev} and {@code state}. The TTL is set
 * once at creation, so a flow expires with its challenge however often it is resumed.
 */
final class RedisFlowStore implements FlowStore {

    private static final RedisScript<Long> CREATE = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            redis.call('HSET', KEYS[1], 'rev', ARGV[1], 'state', ARGV[2])
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return 1
            """, Long.class);

    private static final RedisScript<Long> REPLACE = RedisScript.of("""
            if redis.call('HGET', KEYS[1], 'rev') ~= ARGV[1] then return 0 end
            redis.call('HSET', KEYS[1], 'rev', ARGV[2], 'state', ARGV[3])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    RedisFlowStore(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public Optional<FlowState> load(String flowId) {
        Object json = redis.opsForHash().get(key(flowId), "state");
        return Optional.ofNullable(json).map(j -> FlowStateCodec.read((String) j));
    }

    @Override
    public void create(FlowState state) {
        Long created = redis.execute(
                CREATE,
                List.of(key(state.flowId())),
                Long.toString(state.revision()),
                FlowStateCodec.write(state),
                Long.toString(ttl.toMillis()));
        if (created == null || created != 1) {
            throw new IllegalStateException("flow exists: " + state.flowId());
        }
    }

    @Override
    public boolean replace(FlowState next) {
        Long replaced = redis.execute(
                REPLACE,
                List.of(key(next.flowId())),
                Long.toString(next.revision() - 1),
                Long.toString(next.revision()),
                FlowStateCodec.write(next));
        return replaced != null && replaced == 1;
    }

    private static String key(String flowId) {
        return "flow:" + flowId;
    }
}
