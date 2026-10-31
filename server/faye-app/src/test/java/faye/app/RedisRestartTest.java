package faye.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * M1 exit criterion: stopping the server mid flow does not lose the user's progress. Needs Redis
 * on REDIS_HOST:REDIS_PORT (default localhost:6379); skipped without it unless FAYE_REQUIRE_REDIS is set.
 */
class RedisRestartTest {

    static final String HOST = System.getenv().getOrDefault("REDIS_HOST", "localhost");
    static final int PORT = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));

    @BeforeAll
    static void requireRedis() {
        boolean up;
        try (var socket = new Socket(HOST, PORT)) {
            up = socket.isConnected();
        } catch (Exception e) {
            up = false;
        }
        if (System.getenv("FAYE_REQUIRE_REDIS") != null) {
            assertThat(up).as("Redis at " + HOST + ":" + PORT).isTrue();
        }
        assumeTrue(up, "Redis not reachable at " + HOST + ":" + PORT);
    }

    static ConfigurableApplicationContext boot(String... properties) {
        return new SpringApplicationBuilder(FayeApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.data.redis.host=" + HOST, "spring.data.redis.port=" + PORT)
                .properties(properties)
                .run();
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
     * M3: a published version and the tenant's current pointer live in Redis, so after a restart a
     * new login still runs the version published through the admin API, and a flow pinned to it
     * resumes on it. The tenant id is new on every run because Redis keeps versions.
     */
    @Test
    void publishedVersionSurvivesARestart() {
        String tenant = "restart-" + UUID.randomUUID();
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
}
