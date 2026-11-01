package faye.identity;

import faye.engine.spi.Account;
import faye.engine.spi.PasskeyCredential;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;

/** The prototype's adapters keep the SPI contracts the engine relies on. */
class LocalCredentialsContractTest {

    static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    static final String SECRET = "JBSWY3DPEHPK3PXP";

    UserStore users;
    LocalCredentials creds;

    @BeforeEach
    void setUp() {
        users = new InMemoryUserStore();
        users.save(new User(
                "user-alice",
                "alice@example.com",
                "Alice",
                true,
                Passwords.hash("right"),
                SECRET,
                -1,
                List.of(),
                false));
        users.save(new User(
                "user-bob", "bob@example.com", null, true, Passwords.hash("right"), null, -1, List.of(), false));
        users.save(new User("contract-enrolled", "enrolled@example.com", null, true, null, null, -1, List.of(), false));
        creds = new LocalCredentials(
                users,
                new WebAuthnSettings("localhost", Set.of("http://localhost:3000")),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Nested
    class PasswordContract extends faye.engine.testing.PasswordAuthenticatorContract {
        @Override
        protected faye.engine.spi.PasswordAuthenticator authenticator() {
            return creds;
        }

        @Override
        protected Account account() {
            return creds.bySubject("user-alice").orElseThrow();
        }

        @Override
        protected String password() {
            return "right";
        }
    }

    @Nested
    class OtpContract extends faye.engine.testing.OtpVerifierContract {
        @Override
        protected faye.engine.spi.OtpVerifier verifier() {
            return creds;
        }

        @Override
        protected String enrolledSubject() {
            return "user-alice";
        }

        @Override
        protected String secret() {
            return SECRET;
        }

        @Override
        protected String unenrolledSubject() {
            return "user-bob";
        }

        @Override
        protected long currentStep() {
            return NOW.getEpochSecond() / Totp.PERIOD_SECONDS;
        }

        @Override
        protected String code(String secret, long step) {
            return Totp.code(Base32.decode(secret), step);
        }
    }

    @Nested
    class PasskeyContract extends faye.engine.testing.PasskeyAuthenticatorContract {
        @Override
        protected faye.engine.spi.PasskeyAuthenticator authenticator() {
            return creds;
        }

        @Override
        protected String subject() {
            return "user-alice";
        }

        @Override
        protected String otherSubject() {
            return "user-bob";
        }

        @Override
        protected PasskeyCredential credential(String credentialId) {
            return new PasskeyCredential(
                    credentialId,
                    Map.of(
                            "attestedCredentialData",
                            Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[] {1, 2, 3}),
                            "signCount",
                            0));
        }
    }
}
