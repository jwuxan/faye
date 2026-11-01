package faye.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import faye.engine.spi.CodeOutbox.Channel;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * Where each tenant's one time codes go, by channel (ADR 0032): the tenant's webhook, else the demo
 * outbox directory when one is set, else nowhere, and the channel is off. Faye ships no provider SDK
 * and no SMTP client: an operator's endpoint hands a code to their provider.
 */
final class CodeDelivery {

    /** The handlers a channel's blocks call; refused at publish for a tenant whose channel is off. */
    static final Map<Channel, Set<String>> HANDLERS = Map.of(
            Channel.EMAIL, Set.of("email_code.verify"),
            Channel.SMS, Set.of("sms_code.verify", "phone.enroll"));

    /** A tenant's codes per hour on one channel when its webhook sets no ceiling. */
    static final int DEFAULT_PER_HOUR = 100;

    /** How long one delivery may take before it counts as failed. */
    static final Duration DEADLINE = Duration.ofSeconds(5);

    /** One code to deliver. */
    record Message(String tenant, Channel channel, String to, String code) {}

    /** Hands a message to its provider; throws when it was not accepted. */
    interface Sender {
        void send(Message message) throws IOException;
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FayeProperties props;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(DEADLINE)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    CodeDelivery(FayeProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        props.tenants().forEach((id, t) -> {
            if (t.delivery() == null) return;
            for (Channel c : Channel.values()) webhook(id, c).ifPresent(w -> checkUrl(id, c, w.url()));
        });
    }

    /** Whether {@code tenant} can deliver on {@code channel}. */
    boolean configured(String tenant, Channel channel) {
        return props.tenants().containsKey(tenant) && (webhook(tenant, channel).isPresent() || demoOutbox() != null);
    }

    /** The handlers a tenant's flows may not call, because their channel is off. */
    Set<String> unavailable(String tenant) {
        Set<String> out = new java.util.TreeSet<>();
        for (Channel c : Channel.values()) {
            if (!configured(tenant, c)) out.addAll(HANDLERS.get(c));
        }
        return out;
    }

    int perHour(String tenant, Channel channel) {
        return webhook(tenant, channel)
                .map(FayeProperties.Webhook::perHour)
                .filter(n -> n != null && n > 0)
                .orElse(DEFAULT_PER_HOUR);
    }

    /** The sender of a configured channel. */
    Sender sender(String tenant, Channel channel) {
        Optional<FayeProperties.Webhook> webhook = webhook(tenant, channel);
        if (webhook.isPresent()) return message -> post(webhook.get(), message);
        String dir = demoOutbox();
        if (dir == null) {
            throw new IllegalStateException("tenant " + tenant + " has no " + channel.wireName() + " delivery");
        }
        return message -> write(Path.of(dir), message);
    }

    private Optional<FayeProperties.Webhook> webhook(String tenant, Channel channel) {
        FayeProperties.Tenant t = props.tenants().get(tenant);
        if (t == null || t.delivery() == null) return Optional.empty();
        FayeProperties.Webhook w =
                channel == Channel.EMAIL ? t.delivery().email() : t.delivery().sms();
        return w == null || w.url() == null || w.url().isBlank() ? Optional.empty() : Optional.of(w);
    }

    private @Nullable String demoOutbox() {
        String dir = props.demoOutbox();
        return dir == null || dir.isBlank() ? null : dir;
    }

    /** https, or http to a loopback address (a relay on the same host); refused at startup otherwise. */
    private static void checkUrl(String tenant, Channel channel, String url) {
        URI uri = URI.create(url);
        boolean ok = "https".equals(uri.getScheme())
                || ("http".equals(uri.getScheme()) && uri.getHost() != null && loopback(uri.getHost()));
        if (!ok || uri.getHost() == null) {
            throw new IllegalStateException("faye.tenants." + tenant + ".delivery." + channel.wireName()
                    + ".url must be https, or http to a loopback address");
        }
    }

    private static boolean loopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * POSTs {@code {channel, to, code, tenant}}, signed: header {@code Faye-Signature: t=<epoch
     * seconds>,v1=<hex HMAC-SHA256 of "<t>.<body>">} under the webhook's secret. Any status but 2xx
     * is a failure; redirects are not followed.
     */
    private void post(FayeProperties.Webhook webhook, Message message) throws IOException {
        byte[] body = body(message);
        long t = clock.instant().getEpochSecond();
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhook.url()))
                .timeout(DEADLINE)
                .header("Content-Type", "application/json")
                .header("Faye-Signature", "t=" + t + ",v1=" + sign(webhook.secret(), t, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            int status =
                    http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status / 100 != 2) throw new IOException("webhook answered " + status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    /** The demo's delivery: one JSON file per code, read with {@code docker exec}. */
    private static void write(Path dir, Message message) throws IOException {
        Files.createDirectories(dir);
        String name = message.channel().wireName() + "-" + message.to().replaceAll("[^A-Za-z0-9@.+_-]", "_") + ".json";
        // The newest code for a destination replaces the last, so the demo reads one file
        Files.write(
                dir.resolve(name),
                body(message),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    static byte[] body(Message message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channel", message.channel().wireName());
        out.put("to", message.to());
        out.put("code", message.code());
        out.put("tenant", message.tenant());
        try {
            return JSON.writeValueAsBytes(out);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sign(String secret, long t, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
