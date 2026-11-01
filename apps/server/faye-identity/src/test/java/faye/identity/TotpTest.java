package faye.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TotpTest {

    /** RFC 6238 appendix B, SHA-1 seed; the 6 digit code is the last 6 digits of the 8 digit one. */
    static final byte[] SEED = "12345678901234567890".getBytes(StandardCharsets.UTF_8);

    @Test
    void matchesRfc6238Vectors() {
        assertThat(Totp.code(SEED, 59 / 30)).isEqualTo("287082");
        assertThat(Totp.code(SEED, 1111111109L / 30)).isEqualTo("081804");
        assertThat(Totp.code(SEED, 2000000000L / 30)).isEqualTo("279037");
    }

    @Test
    void acceptsOneStepOfSkewEitherWay() {
        long now = 1111111109L;
        long step = now / 30;
        assertThat(Totp.match(SEED, Totp.code(SEED, step - 1), now)).isEqualTo(step - 1);
        assertThat(Totp.match(SEED, Totp.code(SEED, step + 1), now)).isEqualTo(step + 1);
        assertThat(Totp.match(SEED, Totp.code(SEED, step + 2), now)).isEqualTo(-1);
    }

    @Test
    void base32RoundTrips() {
        assertThat(Base32.encode(SEED)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Base32.decode("gezd gnbv gy3t qojq gezd gnbv gy3t qojq")).isEqualTo(SEED);
    }
}
