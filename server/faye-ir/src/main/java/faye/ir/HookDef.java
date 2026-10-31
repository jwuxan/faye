package faye.ir;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * A hook as stored: its JavaScript source and what it declared it needs. Addressed by the hash of
 * its canonical JSON, so changing the source, a capability or the timeout makes a new hook, which
 * only a new flow version can reference.
 *
 * @param source a module whose default export is {@code (ctx, cap) => result}, possibly async
 * @param http named egress targets the hook may call with {@code cap.http.getJson}; sorted
 * @param timeoutMs wall clock deadline of one run, egress included
 */
public record HookDef(String source, List<String> http, int timeoutMs) {

    public static final int DEFAULT_TIMEOUT_MS = 200;
    public static final int MAX_TIMEOUT_MS = 2000;
    public static final int MAX_SOURCE_BYTES = 64 * 1024;
    public static final Pattern TARGET = Pattern.compile("[a-z][a-z0-9_-]*");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public HookDef {
        if (source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("hook source is larger than " + MAX_SOURCE_BYTES + " bytes");
        }
        if (timeoutMs < 1 || timeoutMs > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException("hook timeout must be 1 to " + MAX_TIMEOUT_MS + " ms");
        }
        for (String t : http) {
            if (!TARGET.matcher(t).matches()) throw new IllegalArgumentException("bad egress target " + t);
        }
        http = List.copyOf(new TreeSet<>(http));
    }

    /** Compact JSON with sorted keys: {@code {"capabilities":{"http":[...]},"source":...,"timeout_ms":...}}. */
    public String canonicalJson() {
        ObjectNode out = MAPPER.createObjectNode();
        out.putObject("capabilities").putPOJO("http", http);
        out.put("source", source);
        out.put("timeout_ms", timeoutMs);
        try {
            return MAPPER.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code sha256:<hex>} of the canonical JSON; what a {@link Hook} node pins. */
    public String hash() {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(canonicalJson().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static HookDef readJson(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            List<String> http = new ArrayList<>();
            node.path("capabilities").path("http").forEach(t -> http.add(t.asText()));
            if (!node.path("source").isTextual() || !node.path("timeout_ms").isInt()) {
                throw new IllegalArgumentException("not a hook definition");
            }
            return new HookDef(
                    node.get("source").textValue(), http, node.get("timeout_ms").intValue());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not JSON: " + e.getOriginalMessage(), e);
        }
    }
}
