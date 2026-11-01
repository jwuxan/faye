package faye.engine.spi;

import org.jspecify.annotations.Nullable;

/**
 * Where an issued one time code goes to be delivered by email or text message (ADR 0032). Called on
 * the request path, so it must only record the code locally (an outbox row); a separate worker hands
 * it to the operator's provider, so a slow provider never slows a screen. It also holds the limits
 * that span flows: sends per destination and per tenant and channel.
 */
public interface CodeOutbox {

    /** How a code reaches the person. */
    enum Channel {
        EMAIL,
        SMS;

        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Records {@code code} for delivery.
     *
     * @param destination the verified address or number; null when there is none (an unknown
     *     account): the row is recorded and never delivered, so the request costs what a real one
     *     costs (enumeration resistance)
     * @param limitKey what the per destination limits count: the destination, or for an unknown
     *     account what the person typed, so both reach a limit alike
     * @return false, recording nothing, when a limit refuses the send
     * @throws RuntimeException when the tenant has no delivery configured for {@code channel}; the
     *     checker refuses such a flow at publish, so this is a misconfiguration
     */
    boolean enqueue(String tenant, Channel channel, @Nullable String destination, String limitKey, String code);
}
