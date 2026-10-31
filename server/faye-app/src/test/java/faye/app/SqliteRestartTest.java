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
            var identifier = (ScreenView) engine.start(HostKind.LOGIN, "c-restart", Map.of());
            var password = (ScreenView) engine.submit(
                    identifier.flowId(), identifier.nonce(), new Submission(Map.of("identifier", "alice@example.com")));
            flowId = password.flowId();
            nonce = password.nonce();
        }

        try (var after = boot()) {
            Engine engine = after.getBean(Engine.class);
            ScreenView resumed = engine.view(flowId);
            assertThat(resumed.screen())
                    .containsEntry("type", "password")
                    .containsEntry("identifier", "alice@example.com");
            assertThat(resumed.nonce()).isEqualTo(nonce);

            var done = engine.submit(flowId, nonce, new Submission(Map.of("password", "correct horse battery staple")));
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
            var identifier = (ScreenView) engine.start(HostKind.LOGIN, "c-published", Map.of());
            password = (ScreenView) engine.submit(
                    identifier.flowId(), identifier.nonce(), new Submission(Map.of("identifier", "alice@example.com")));
        }

        try (var after = boot(config)) {
            assertThat(after.getBean(FlowVersions.class).current(tenant))
                    .hasValueSatisfying(v -> assertThat(v.hash()).isEqualTo(published));
            Engine engine = after.getBean(Engine.class);
            var otp = (ScreenView) engine.submit(
                    password.flowId(),
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
}
