package faye.engine.spi;

import java.util.Map;

/**
 * A registered passkey.
 *
 * @param credentialId base64url credential id
 * @param data the credential material the adapter needs (public key, sign count); opaque to the
 *     engine, JSON-safe (strings, numbers, booleans), carried in the {@code write} Effect
 */
public record PasskeyCredential(String credentialId, Map<String, Object> data) {

    public PasskeyCredential {
        data = Map.copyOf(data);
    }
}
