package faye.app;

import faye.engine.HookStore;
import faye.ir.HookDef;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Hook definitions in Redis: {@code hook:{hash}} holds a definition's canonical JSON. Like
 * versions, hooks never expire, so a version that pins one can always run it.
 */
final class RedisHookStore implements HookStore {

    private final StringRedisTemplate redis;
    /** Hooks are immutable, so each is read and verified once. */
    private final Map<String, HookDef> cache = new ConcurrentHashMap<>();

    RedisHookStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void put(HookDef def) {
        redis.opsForValue().setIfAbsent(key(def.hash()), def.canonicalJson());
    }

    @Override
    public Optional<HookDef> get(String hash) {
        HookDef cached = cache.get(hash);
        if (cached != null) return Optional.of(cached);
        String json = redis.opsForValue().get(key(hash));
        if (json == null) return Optional.empty();
        HookDef def = HookDef.readJson(json);
        if (!def.hash().equals(hash)) {
            throw new IllegalStateException("stored hook " + hash + " hashes to " + def.hash());
        }
        cache.put(hash, def);
        return Optional.of(def);
    }

    private static String key(String hash) {
        return "hook:" + hash;
    }
}
