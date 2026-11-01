package faye.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import faye.engine.CredentialIssuer;
import faye.engine.Env;
import faye.engine.testing.InMemoryCredentials;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What the built in issuers issue for the screens that show credential material (ADR 0025). */
class BuiltinIssuersTest {

    final InMemoryCredentials creds = new InMemoryCredentials();

    private CredentialIssuer issuer(String screen) {
        return BuiltinIssuers.create(creds, creds).stream()
                .filter(i -> i.screen().equals(screen))
                .findFirst()
                .orElseThrow();
    }

    private static Env env(Map<String, Object> tenant, Map<String, Object> client, Env.Auth auth) {
        return new Env(Map.of(), client, tenant, Map.of(), Map.of("identifier", "alice@example.com"), Map.of(), auth);
    }

    private static final Env ALICE = env(Map.of("name", "Acme Bank"), Map.of(), new Env.Auth("alice", List.of()));

    @Test
    void anEnrollmentSecretAndUriSurviveARetry() {
        CredentialIssuer otp = issuer("otp_enroll");
        Map<String, Object> first = otp.issue(ALICE, Map.of(), Map.of());
        Map<String, Object> again = otp.issue(ALICE, Map.of(), first);
        assertThat(again).isEqualTo(first);
        assertThat(otp.issue(ALICE, Map.of(), Map.of())).isNotEqualTo(first);
        assertThat(otp.present(ALICE, first)).isEqualTo(first);
    }

    @Test
    void theAuthenticatorLabelIsTheTenantsDisplayName() {
        CredentialIssuer otp = issuer("otp_enroll");
        assertThat((String) otp.issue(ALICE, Map.of(), Map.of()).get("uri"))
                .contains("Acme Bank")
                .contains("alice@example.com");
        var signedIn = new Env(
                Map.of(),
                Map.of("client_name", "Partner"),
                Map.of(),
                Map.of(),
                Map.of("identifier", "alice@example.com"),
                Map.of(),
                new Env.Auth("alice", List.of()));
        assertThat((String) otp.issue(signedIn, Map.of(), Map.of()).get("uri")).contains("Partner");
        var nobody = new Env(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("identifier", "alice@example.com"),
                Map.of(),
                new Env.Auth("alice", List.of()));
        assertThat((String) otp.issue(nobody, Map.of(), Map.of()).get("uri")).contains("Sign in");
    }

    @Test
    void aPasskeyRegistrationWithoutASubjectFailsToIssue() {
        CredentialIssuer passkey = issuer("passkey");
        Env nobody = env(Map.of(), Map.of(), Env.Auth.NONE);
        assertThatThrownBy(() -> passkey.issue(nobody, Map.of("register", true), Map.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(passkey.issue(nobody, Map.of(), Map.of())).containsKey("challenge");
    }

    @Test
    void registrationOffersTheAlgorithmsTheAuthenticatorVerifies() {
        CredentialIssuer passkey = issuer("passkey");
        Map<String, Object> issued = passkey.issue(ALICE, Map.of("register", true), Map.of());
        Map<String, Object> data = new java.util.HashMap<>(issued);
        data.put("register", true);
        Map<String, Object> shown = passkey.present(ALICE, data);
        assertThat(shown).containsEntry("challenge", issued.get("challenge"));
        Map<?, ?> register = (Map<?, ?>) java.util.Objects.requireNonNull(shown.get("register"));
        assertThat(register.get("algorithms")).isEqualTo(creds.algorithms());
    }

    @Test
    void autofillIsIssuedOnlyWhenTheIdentifierScreenOffersIt() {
        CredentialIssuer identifier = issuer("identifier");
        assertThat(identifier.issue(ALICE, Map.of(), Map.of())).isEmpty();
        Map<String, Object> issued = identifier.issue(ALICE, Map.of("passkey", true), Map.of());
        assertThat(identifier.present(ALICE, issued))
                .isEqualTo(Map.of("passkey", Map.of("challenge", issued.get("challenge"))));
    }
}
