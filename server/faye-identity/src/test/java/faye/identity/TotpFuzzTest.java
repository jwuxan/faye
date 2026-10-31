package faye.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * F8 (ADR 0007): base32 round trips and refuses anything else with an {@link
 * IllegalArgumentException} only (O1, O9); a TOTP code matches only within one step of now, only
 * for the step it was made for, and agrees with a reference written from RFC 4226 and RFC 6238
 * (O10).
 */
class TotpFuzzTest {

    @FuzzTest(maxDuration = "60s")
    void base32(FuzzedDataProvider data) {
        byte[] bytes = data.consumeBytes(64);
        assertThat(Base32.decode(Base32.encode(bytes))).isEqualTo(bytes);
        assertThat(Base32.decode(Base32.encode(bytes).toLowerCase(java.util.Locale.ROOT)))
                .isEqualTo(bytes);
        String text = data.consumeRemainingAsString();
        try {
            byte[] decoded = Base32.decode(text);
            // Whatever decodes is made of the alphabet, padding and spaces
            assertThat(text.toUpperCase(java.util.Locale.ROOT)).matches("[A-Z2-7= ]*");
            assertThat(decoded.length).isLessThanOrEqualTo(text.length());
        } catch (IllegalArgumentException refused) {
            // declared
        }
    }

    @FuzzTest(maxDuration = "60s")
    void totp(FuzzedDataProvider data) {
        byte[] secret = data.consumeBytes(32);
        if (secret.length == 0) secret = new byte[] {0};
        // Any instant a clock could read, from 1970 to the far future
        long now = data.consumeLong(0, Long.MAX_VALUE / 2);
        long step = now / Totp.PERIOD_SECONDS;
        long delta = data.consumeLong(-3, 3);
        String code = reference(secret, step + delta);
        assertThat(Totp.code(secret, step + delta)).isEqualTo(code);
        long matched = Totp.match(secret, code, now);
        if (Math.abs(delta) <= 1) {
            // The earliest step in the window with that code: another step may share it by chance
            assertThat(matched).isBetween(step - 1, step + delta);
        } else if (matched >= 0) {
            assertThat(reference(secret, matched))
                    .as("only a real code of the window matches")
                    .isEqualTo(code);
        }
        // A typed code that is not one of the window's three never matches
        String typed = data.consumeString(8);
        long m = Totp.match(secret, typed, now);
        if (m >= 0) {
            assertThat(m).isBetween(step - 1, step + 1);
            assertThat(reference(secret, m)).isEqualTo(typed);
        }
    }

    /** RFC 6238, appendix B, SHA-1, cut to six digits. */
    @Test
    void rfc6238Vectors() {
        byte[] secret = "12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        long[][] vectors = {
            {59, 94287082},
            {1111111109, 7081804},
            {1111111111, 14050471},
            {1234567890, 89005924},
            {2000000000, 69279037},
            {20000000000L, 65353130}
        };
        for (long[] v : vectors) {
            String six = String.format("%06d", v[1] % 1_000_000);
            assertThat(Totp.code(secret, v[0] / 30)).as("T=" + v[0]).isEqualTo(six);
            assertThat(Totp.match(secret, six, v[0])).isEqualTo(v[0] / 30);
        }
    }

    /** Base32 refuses what is not base32, and an empty secret is not a key. */
    @Test
    void base32RefusesJunk() {
        assertThatThrownBy(() -> Base32.decode("ABC1")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Base32.decode("")).isEmpty();
        assertThat(Arrays.equals(Base32.decode("me======"), Base32.decode("ME")))
                .isTrue();
    }

    /** HOTP (RFC 4226 section 5.3), written apart from {@link Totp}. */
    static String reference(byte[] secret, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "RAW"));
            byte[] hash =
                    mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
            int offset = hash[19] & 0x0f;
            long truncated = ByteBuffer.wrap(hash, offset, 4).getInt() & 0x7fffffffL;
            return String.format("%06d", truncated % 1_000_000L);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
