package faye.app;

import faye.engine.FlowVersion;
import faye.engine.FlowVersions;
import faye.ir.IrCodec;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Flow versions in Redis: {@code version:{hash}} holds a version's canonical IR, {@code
 * tenant:{id}:history} the hashes a tenant published, and {@code tenant:{id}:current} its current
 * one. Versions never expire, so a flow pinned to one can always finish.
 */
final class RedisFlowVersions implements FlowVersions {

    private static final RedisScript<Long> PUBLISH = RedisScript.of("""
            redis.call('SET', KEYS[1], ARGV[2], 'NX')
            if not redis.call('LPOS', KEYS[2], ARGV[1]) then redis.call('RPUSH', KEYS[2], ARGV[1]) end
            redis.call('SET', KEYS[3], ARGV[1])
            return 1
            """, Long.class);

    private static final RedisScript<Long> ROLLBACK = RedisScript.of("""
            if not redis.call('LPOS', KEYS[1], ARGV[1]) then return 0 end
            redis.call('SET', KEYS[2], ARGV[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    /** Versions are immutable, so each is read and verified once. */
    private final Map<String, FlowVersion> cache = new ConcurrentHashMap<>();

    RedisFlowVersions(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public FlowVersion publish(String tenantId, FlowVersion version) {
        redis.execute(
                PUBLISH,
                List.of(versionKey(version.hash()), historyKey(tenantId), currentKey(tenantId)),
                version.hash(),
                IrCodec.canonicalJson(version.flow()));
        return version;
    }

    @Override
    public void store(FlowVersion version) {
        redis.opsForValue().setIfAbsent(versionKey(version.hash()), IrCodec.canonicalJson(version.flow()));
    }

    @Override
    public Optional<FlowVersion> current(String tenantId) {
        return Optional.ofNullable(redis.opsForValue().get(currentKey(tenantId)))
                .flatMap(this::get);
    }

    @Override
    public Optional<FlowVersion> get(String hash) {
        FlowVersion cached = cache.get(hash);
        if (cached != null) return Optional.of(cached);
        String json = redis.opsForValue().get(versionKey(hash));
        if (json == null) return Optional.empty();
        FlowVersion version = FlowVersion.of(IrCodec.readFlow(json));
        if (!version.hash().equals(hash)) {
            throw new IllegalStateException("stored version " + hash + " hashes to " + version.hash());
        }
        cache.put(hash, version);
        return Optional.of(version);
    }

    @Override
    public List<String> history(String tenantId) {
        return Objects.requireNonNullElse(redis.opsForList().range(historyKey(tenantId), 0, -1), List.of());
    }

    @Override
    public boolean rollback(String tenantId, String hash) {
        Long moved = redis.execute(ROLLBACK, List.of(historyKey(tenantId), currentKey(tenantId)), hash);
        return moved != null && moved == 1;
    }

    private static String versionKey(String hash) {
        return "version:" + hash;
    }

    private static String historyKey(String tenantId) {
        return "tenant:" + tenantId + ":history";
    }

    private static String currentKey(String tenantId) {
        return "tenant:" + tenantId + ":current";
    }
}
