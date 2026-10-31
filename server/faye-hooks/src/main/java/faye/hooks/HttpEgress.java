package faye.hooks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import faye.engine.HookException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;
import org.jspecify.annotations.Nullable;

/**
 * {@link Egress} over HTTP to configured base URLs. Redirects are not followed, so a target cannot
 * send a hook somewhere else, and a response larger than {@link #MAX_RESPONSE_BYTES} is refused.
 */
public final class HttpEgress implements Egress {

    static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, URI> targets;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /** @param targets base URL per target name */
    public HttpEgress(Map<String, URI> targets) {
        this.targets = Map.copyOf(targets);
    }

    @Override
    public @Nullable Object getJson(String target, Map<String, Object> query, Duration timeout) {
        URI base = targets.get(target);
        if (base == null) throw new HookException("unknown egress target " + target);
        StringJoiner params = new StringJoiner("&");
        query.forEach((k, v) -> {
            if (!(v instanceof String || v instanceof Number || v instanceof Boolean)) {
                throw new HookException("query values must be strings, numbers or booleans");
            }
            params.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8));
        });
        String sep = base.getRawQuery() == null ? "?" : "&";
        URI uri = URI.create(base + (query.isEmpty() ? "" : sep + params));
        if (timeout.isZero()) throw new HookException(target + ": no time left");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() / 100 != 2) {
                    throw new HookException(target + " answered " + response.statusCode());
                }
                byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) throw new HookException(target + ": response too large");
                return JSON.readValue(bytes, Object.class);
            }
        } catch (JsonProcessingException e) {
            throw new HookException(target + ": response is not JSON", e);
        } catch (IOException e) {
            throw new HookException(target + ": " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HookException(target + ": interrupted", e);
        }
    }
}
