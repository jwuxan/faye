package faye.engine.testing;

import faye.engine.spi.Account;
import faye.engine.spi.PasskeyCredential;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;

/** The deterministic in memory SPIs keep the same contracts real adapters must. */
class InMemoryCredentialsTest {

    InMemoryCredentials creds;

    @BeforeEach
    void setUp() {
        creds = new InMemoryCredentials();
        creds.addUser("user-alice", "alice@example.com", "right", "SECRET");
        creds.addUser("user-bob", "bob@example.com", "right", null);
        creds.addUser("contract-enrolled", "enrolled@example.com", null, null);
    }

    @Nested
    class PasswordContract extends PasswordAuthenticatorContract {
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
    class OtpContract extends OtpVerifierContract {
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
            return "SECRET";
        }

        @Override
        protected String unenrolledSubject() {
            return "user-bob";
        }

        @Override
        protected long currentStep() {
            return InMemoryCredentials.NOW;
        }

        @Override
        protected String code(String secret, long step) {
            return InMemoryCredentials.code(secret, step);
        }
    }

    @Nested
    class PasskeyContract extends PasskeyAuthenticatorContract {
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
            return new PasskeyCredential(credentialId, Map.of());
        }
    }
}
