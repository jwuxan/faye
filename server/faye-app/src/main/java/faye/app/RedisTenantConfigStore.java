package faye.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link TenantConfigStore} in Redis: {@code tenant:{id}:draft} and {@code version:{hash}:source}
 * hold YAML, {@code tenant:{id}:settings} and {@code tenant:{id}:scenario} JSON. None expire.
 */
final class RedisTenantConfigStore implements TenantConfigStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final StringRedisTemplate redis;

    RedisTenantConfigStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<String> draft(String tenantId) {
        return Optional.ofNullable(redis.opsForValue().get("tenant:" + tenantId + ":draft"));
    }

    @Override
    public void saveDraft(String tenantId, String yaml) {
        redis.opsForValue().set("tenant:" + tenantId + ":draft", yaml);
    }

    @Override
    public Optional<String> source(String versionHash) {
        return Optional.ofNullable(redis.opsForValue().get("version:" + versionHash + ":source"));
    }

    @Override
    public void saveSource(String versionHash, String yaml) {
        redis.opsForValue().setIfAbsent("version:" + versionHash + ":source", yaml);
    }

    @Override
    public Optional<TenantSettings> settings(String tenantId) {
        String json = redis.opsForValue().get("tenant:" + tenantId + ":settings");
        if (json == null) return Optional.empty();
        try {
            return Optional.of(JSON.readValue(json, TenantSettings.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unreadable settings for tenant " + tenantId, e);
        }
    }

    @Override
    public void saveSettings(String tenantId, TenantSettings settings) {
        try {
            redis.opsForValue().set("tenant:" + tenantId + ":settings", JSON.writeValueAsString(settings));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<PreviewScenario> scenario(String tenantId) {
        String json = redis.opsForValue().get("tenant:" + tenantId + ":scenario");
        if (json == null) return Optional.empty();
        try {
            return Optional.of(JSON.readValue(json, PreviewScenario.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unreadable preview scenario for tenant " + tenantId, e);
        }
    }

    @Override
    public void saveScenario(String tenantId, PreviewScenario scenario) {
        try {
            redis.opsForValue().set("tenant:" + tenantId + ":scenario", JSON.writeValueAsString(scenario));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
