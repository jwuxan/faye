package faye.handlers;

import static org.assertj.core.api.Assertions.assertThat;

import faye.engine.Env;
import faye.engine.Handler;
import faye.engine.HandlerRegistry;
import faye.engine.Invocation;
import faye.engine.Outcome;
import faye.engine.Submission;
import faye.engine.testing.InMemoryCredentials;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The built in handlers' policies over the credential SPIs: enumeration, visibility, step up. */
class BuiltinHandlersTest {

    final InMemoryCredentials creds = new InMemoryCredentials();
    HandlerRegistry handlers;

    @BeforeEach
    void setUp() {
        creds.addUser(
                "user-alice", "alice@example.com", "Alice", true, "right", "SECRET", List.of(), false, null, false);
        creds.addUser("user-bob", "bob@example.com", null, true, "right", null, List.of(), false, null, false);
        handlers = new HandlerRegistry(BuiltinHandlers.create(creds, creds, creds, creds));
    }

    Handler resolve() {
        return handlers.find("identity.resolve").orElseThrow();
    }

    Outcome resolve(String identifier) {
        return resolve(identifier, Env.Auth.NONE, "default", false);
    }

    Outcome resolve(String identifier, Env.Auth auth, String tenant, boolean preview) {
        var env = new Env(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), auth);
        return resolve()
                .invoke(new Invocation(
                        env, new Submission(Map.of("identifier", identifier)), Map.of(), "f_test", tenant, preview));
    }

    @Test
    void unknownAccountsResolveLikePasswordOnlyAccounts() {
        var unknown = (Outcome.Ok) resolve("nobody@example.com");
        var bob = (Outcome.Ok) resolve("bob@example.com");
        for (String key : List.of("has_password", "has_totp", "has_passkey", "mfa_required", "identified_by")) {
            assertThat(unknown.user().get(key)).as(key).isEqualTo(bob.user().get(key));
        }
    }

    @Test
    void aStepUpIdentifiesOnlyTheSubjectTheHostKnows() {
        // bob holds a session and steps up; typing alice's identifier must not bring her attributes
        var bob = new Env.Auth("user-bob", List.of());
        assertThat(resolve("alice@example.com", bob, "default", false)).isEqualTo(new Outcome.Fail("wrong_account"));
        assertThat(resolve("bob@example.com", bob, "default", false))
                .isInstanceOfSatisfying(
                        Outcome.Ok.class, ok -> assertThat(ok.user()).containsEntry("email", "bob@example.com"));
    }

    @Test
    void anAccountOfOneTenantIsUnknownToTheClientsOfAnother() {
        creds.addUser(
                "user-carol", "carol@acme.example", "Carol", true, "right", null, List.of(), false, "acme", false);
        assertThat(((Outcome.Ok) resolve("carol@acme.example", Env.Auth.NONE, "acme", false))
                        .user()
                        .get("exists"))
                .isEqualTo(true);
        assertThat(((Outcome.Ok) resolve("carol@acme.example", Env.Auth.NONE, "other", false))
                        .user()
                        .get("exists"))
                .isEqualTo(false);
        assertThat(((Outcome.Ok) resolve("carol@acme.example", Env.Auth.NONE, "default", false))
                        .user()
                        .get("exists"))
                .isEqualTo(false);
        // Accounts every tenant shares still resolve
        assertThat(((Outcome.Ok) resolve("alice@example.com", Env.Auth.NONE, "other", false))
                        .user()
                        .get("exists"))
                .isEqualTo(true);
    }

    @Test
    void anInvisibleAccountCannotVerifyAPassword() {
        creds.addUser("user-carol", "carol@acme.example", null, true, "right", null, List.of(), false, "acme", false);
        var verify = handlers.find("password.verify").orElseThrow();
        var env = new Env(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("identifier", "carol@acme.example"),
                Map.of(),
                Env.Auth.NONE);
        // From another tenant the account is unknown, so the right password still fails
        assertThat(verify.invoke(new Invocation(
                        env, new Submission(Map.of("password", "right")), Map.of(), "f", "other", false)))
                .isEqualTo(new Outcome.Fail("invalid_credentials"));
    }

    @Test
    void aMissingPasskeyAnswerIsInvalidCredentialsNotAnEscape() {
        // The registration answer with no passkey at all, or on a screen that issued no challenge,
        // is a credential failure like any other, not an exception out of the handler (ADR 0007)
        var register = handlers.find("webauthn.register").orElseThrow();
        var env = new Env(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), new Env.Auth("user-alice", List.of()));
        assertThat(register.invoke(new Invocation(env, new Submission(Map.of()), Map.of(), "f", "default", false)))
                .isEqualTo(new Outcome.Fail("invalid_credentials"));
        assertThat(register.invoke(new Invocation(
                        env,
                        new Submission(Map.of("passkey", Map.of("id", "x")), Map.of()),
                        Map.of(),
                        "f",
                        "default",
                        false)))
                .isEqualTo(new Outcome.Fail("invalid_credentials"));

        var assertion = handlers.find("webauthn.assert").orElseThrow();
        assertThat(assertion.invoke(new Invocation(env, new Submission(Map.of()), Map.of(), "f", "default", false)))
                .isEqualTo(new Outcome.Fail("invalid_credentials"));
        assertThat(assertion.invoke(new Invocation(
                        env,
                        new Submission(Map.of("passkey", Map.of("id", "x")), Map.of()),
                        Map.of(),
                        "f",
                        "default",
                        false)))
                .isEqualTo(new Outcome.Fail("invalid_credentials"));
    }

    @Test
    void previewAccountsAreUnknownExceptToPreviewClients() {
        creds.addUser("preview-1", "tester@preview.faye.test", null, true, "right", null, List.of(), false, null, true);
        assertThat(((Outcome.Ok) resolve("tester@preview.faye.test")).user().get("exists"))
                .isEqualTo(false);
        assertThat(((Outcome.Ok) resolve("tester@preview.faye.test", Env.Auth.NONE, "default", true))
                        .user()
                        .get("exists"))
                .isEqualTo(true);
    }

    /** An app's code is a second factor only: with no subject verified yet it proves nothing (ADR 0032). */
    @Test
    void totpNeedsASubjectAlreadyVerified() {
        Handler totp = handlers.find("totp.verify").orElseThrow();
        String code = InMemoryCredentials.code("SECRET", InMemoryCredentials.NOW);
        var identified = new Env(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("identifier", "alice@example.com"),
                Map.of(),
                Env.Auth.NONE);
        assertThat(totp.invoke(new Invocation(
                        identified, new Submission(Map.of("code", code)), Map.of(), "f", "default", false)))
                .isEqualTo(new Outcome.Fail("unauthenticated"));
        var signedIn = new Env(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("identifier", "alice@example.com"),
                Map.of(),
                new Env.Auth("user-alice", List.of(new Env.Verified("password.verify", List.of("pwd")))));
        assertThat(totp.invoke(new Invocation(
                        signedIn, new Submission(Map.of("code", code)), Map.of(), "f", "default", false)))
                .isEqualTo(new Outcome.Ok(Map.of(), "user-alice", null));
        assertThat(totp.meta().requiresSubject()).isTrue();
    }
}
