package faye.identity;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 TOTP: HMAC-SHA1, 6 digits, 30 second steps, accepting one step of clock skew either way. */
final class Totp {

    static final long PERIOD_SECONDS = 30;

    private Totp() {}

    /** @return the time step {@code code} is valid for near {@code epochSecond}, or -1 */
    static long match(byte[] secret, String code, long epochSecond) {
        long now = epochSecond / PERIOD_SECONDS;
        for (long step = now - 1; step <= now + 1; step++) {
            if (java.security.MessageDigest.isEqual(code(secret, step).getBytes(), code.getBytes())) return step;
        }
        return -1;
    }

    static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = h[h.length - 1] & 0xf;
            int binary = ((h[offset] & 0x7f) << 24)
                    | ((h[offset + 1] & 0xff) << 16)
                    | ((h[offset + 2] & 0xff) << 8)
                    | (h[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
