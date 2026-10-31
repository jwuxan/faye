package faye.app;

import static org.assertj.core.api.Assertions.assertThat;

import faye.check.Publisher;
import faye.dsl.FlowCompiler;
import faye.engine.Engine;
import faye.engine.FlowVersions;
import faye.engine.HostKind;
import faye.engine.HostRequest;
import faye.engine.Redirect;
import faye.engine.ScreenView;
import faye.engine.Submission;
import faye.host.mock.MockFlowHost;
import faye.identity.User;
import faye.identity.UserStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * M1 exit criterion: stopping the server mid flow does not lose the user's progress. Each test boots
 * the server twice on one SQLite database file.
 */
class SqliteRestartTest {

    @TempDir
    Path dir;

    ConfigurableApplicationContext boot(String... properties) {
        return new SpringApplicationBuilder(FayeApplication.class)
                .web(WebApplicationType.NONE)
                .properties(properties)
                // An argument, to override the test config's fresh database per context
                .run("--spring.datasource.url=jdbc:sqlite:" + dir.resolve("faye.db")
                        + "?journal_mode=WAL&busy_timeout=10000&transaction_mode=IMMEDIATE");
    }

    @Test
    void flowSurvivesAServerRestart() {
        String flowId;
        String nonce;
        try (var before = boot()) {
            Engine engine = before.getBean(Engine.class);
            var identifier = (ScreenView) engine.start(HostKind.LOGIN, "c-restart", Map.of(), "binder");
            var password = (ScreenView) engine.submit(
                    identifier.flowId(),
                    "binder",
                    identifier.nonce(),
                    new Submission(Map.of("identifier", "alice@example.com")));
            flowId = password.flowId();
            nonce = password.nonce();
        }

        try (var after = boot()) {
            Engine engine = after.getBean(Engine.class);
            ScreenView resumed = engine.view(flowId, "binder");
            assertThat(resumed.screen())
                    .containsEntry("type", "password")
                    .containsEntry("identifier", "alice@example.com");
            assertThat(resumed.nonce()).isEqualTo(nonce);

            var done = engine.submit(
                    flowId, "binder", nonce, new Submission(Map.of("password", "correct horse battery staple")));
            assertThat(done).isInstanceOf(Redirect.class);
            assertThat(after.getBean(MockFlowHost.class).accepted())
                    .singleElement()
                    .satisfies(a -> assertThat(a.outcome().subject()).isEqualTo("user-alice"));
        }
    }

    /**
     * M3: a published version and the tenant's current pointer live in the database, so after a
     * restart a new login still runs the version published through the admin API, and a flow pinned
     * to it resumes on it.
     */
    @Test
    void publishedVersionSurvivesARestart() {
        String tenant = "restart";
        String config = "faye.tenants." + tenant + ".flow=classpath:flows/password-only.yaml";
        Map<String, Object> client = Map.of("client_id", "demo", "metadata", Map.of("tenant", tenant));
        String published;
        ScreenView password;
        try (var before = boot(config)) {
            published = before.getBean(Publisher.class)
                    .publish(tenant, FlowCompiler.compile("totp.yaml", MilestoneThreeTest.PASSWORD_TOTP))
                    .hash();
            before.getBean(MockFlowHost.class)
                    .register(new HostRequest(HostKind.LOGIN, "c-published", client, Map.of()));
            Engine engine = before.getBean(Engine.class);
            var identifier = (ScreenView) engine.start(HostKind.LOGIN, "c-published", Map.of(), "binder");
            password = (ScreenView) engine.submit(
                    identifier.flowId(),
                    "binder",
                    identifier.nonce(),
                    new Submission(Map.of("identifier", "alice@example.com")));
        }

        try (var after = boot(config)) {
            assertThat(after.getBean(FlowVersions.class).current(tenant))
                    .hasValueSatisfying(v -> assertThat(v.hash()).isEqualTo(published));
            Engine engine = after.getBean(Engine.class);
            var otp = (ScreenView) engine.submit(
                    password.flowId(),
                    "binder",
                    password.nonce(),
                    new Submission(Map.of("password", "correct horse battery staple")));
            assertThat(otp.screen()).containsEntry("type", "otp");
        }
    }

    /** An account and what it enrolled stay in its tenant's user pool across a restart. */
    @Test
    void accountsSurviveARestart() {
        var carol = new User(
                "user-carol",
                "carol@example.com",
                "Carol",
                true,
                null,
                "JBSWY3DPEHPK3PXP",
                42,
                List.of(new User.Passkey("cred-1", new byte[] {1, 2, 3}, 7)),
                false,
                false,
                "default");
        try (var before = boot()) {
            before.getBean(UserStore.class).save(carol);
        }
        try (var after = boot()) {
            User found = after.getBean(UserStore.class)
                    .byIdentifier("carol@example.com")
                    .orElseThrow();
            assertThat(found)
                    .usingRecursiveComparison()
                    .ignoringFields("passkeys")
                    .isEqualTo(carol);
            assertThat(found.passkeys()).singleElement().satisfies(p -> {
                assertThat(p.credentialId()).isEqualTo("cred-1");
                assertThat(p.attestedCredentialData()).containsExactly(1, 2, 3);
                assertThat(p.signCount()).isEqualTo(7);
            });
        }
    }

    /**
     * ADR 0024: the SQLite store makes the identity handlers' conditional writes atomically. A TOTP
     * step is used by one flow only, except again by that same flow; a credential id belongs to one
     * account; a sign count only rises.
     */
    @Test
    void conditionalAccountWritesHoldInTheDatabase() {
        try (var app = boot()) {
            UserStore users = app.getBean(UserStore.class);
            users.save(new User("user-g", "g@example.com", null, true, null, "JBSWY3DPEHPK3PXP", 10, List.of(), false));
            users.save(new User("user-h", "h@example.com", null, true, null, null, -1, List.of(), false));

            assertThat(users.useTotpStep("user-g", 10, "f_a"))
                    .as("a step already used")
                    .isFalse();
            assertThat(users.useTotpStep("user-g", 11, "f_a")).isTrue();
            assertThat(users.useTotpStep("user-g", 11, "f_a"))
                    .as("again by the flow that used it")
                    .isTrue();
            assertThat(users.useTotpStep("user-g", 11, "f_b"))
                    .as("by another flow")
                    .isFalse();
            assertThat(users.useTotpStep("user-h", 11, "f_a"))
                    .as("an account without TOTP")
                    .isFalse();

            var key = new User.Passkey("cred-1", new byte[] {1, 2, 3}, 5);
            assertThat(users.addPasskey("user-g", key)).isTrue();
            assertThat(users.addPasskey("user-g", key)).as("idempotent").isTrue();
            assertThat(users.addPasskey("user-h", key))
                    .as("another account's credential id")
                    .isFalse();
            assertThat(users.bySubject("user-g").orElseThrow().passkeys()).hasSize(1);
            assertThat(users.bySubject("user-h").orElseThrow().passkeys()).isEmpty();

            users.raiseSignCount("user-g", "cred-1", 3);
            assertThat(users.bySubject("user-g").orElseThrow().passkeys().get(0).signCount())
                    .isEqualTo(5);
            users.raiseSignCount("user-g", "cred-1", 9);
            assertThat(users.bySubject("user-g").orElseThrow().passkeys().get(0).signCount())
                    .isEqualTo(9);

            users.enrollTotp("user-h", "KRSXG5CTMVRXEZLU", 20, "f_c");
            assertThat(users.bySubject("user-h").orElseThrow().totpSecret()).isEqualTo("KRSXG5CTMVRXEZLU");
            assertThat(users.useTotpStep("user-h", 20, "f_c")).isTrue();
            assertThat(users.useTotpStep("user-h", 20, "f_d")).isFalse();
        }
    }

    /**
     * ADR 0024: a version stored only for a preview is swept once it is older than the flow TTL and
     * nothing references it, but storing it again (the draft previewed again) renews it.
     */
    @Test
    void aStoredDraftIsSweptUnlessStoredAgain() {
        try (var app = boot()) {
            var db = app.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
            var tx = app.getBean(org.springframework.transaction.support.TransactionTemplate.class);
            java.time.Duration ttl = java.time.Duration.ofMinutes(30);
            java.time.Instant t0 = java.time.Instant.parse("2026-10-31T00:00:00Z");
            java.util.function.Function<java.time.Instant, SqliteFlowVersions> at =
                    t -> new SqliteFlowVersions(db, tx, ttl, java.time.Clock.fixed(t, java.time.ZoneOffset.UTC));
            var draft = faye.engine.FlowVersion.of(FlowCompiler.compile("d.yaml", """
                    flow: draft
                    version: 1
                    login:
                      steps:
                        identify: { screen: identifier, use: identity.resolve }
                        credential: { screen: password, use: password.verify }
                    """));
            var other = faye.engine.FlowVersion.of(FlowCompiler.compile("o.yaml", """
                    flow: other
                    version: 1
                    login:
                      steps:
                        identify: { screen: identifier, use: identity.resolve }
                        credential: { screen: password, use: password.verify }
                    """));
            String count = "SELECT count(*) FROM faye_version WHERE hash = ?";

            at.apply(t0).store(draft);
            // Previewed again after a TTL: renewed, so the sweep that runs with this store keeps it
            at.apply(t0.plus(ttl.multipliedBy(2))).store(draft);
            assertThat(db.sql(count).param(draft.hash()).query(Integer.class).single())
                    .isEqualTo(1);
            // Not stored again for a TTL: the next store's sweep deletes it
            at.apply(t0.plus(ttl.multipliedBy(4))).store(other);
            assertThat(db.sql(count).param(draft.hash()).query(Integer.class).single())
                    .isZero();
            assertThat(db.sql(count).param(other.hash()).query(Integer.class).single())
                    .isEqualTo(1);
        }
    }
}
