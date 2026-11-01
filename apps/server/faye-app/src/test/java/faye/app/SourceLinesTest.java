package faye.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import faye.dsl.FlowCompiler;
import faye.engine.Engine;
import faye.engine.FailureSite;
import faye.engine.Site;
import faye.engine.Submission;
import faye.engine.spi.Authenticated;
import faye.engine.spi.Completion;
import faye.engine.spi.Consented;
import faye.engine.spi.FlowHost;
import faye.engine.spi.FlowStore;
import faye.engine.spi.FlowVersions;
import faye.engine.spi.HostKind;
import faye.engine.spi.HostParams;
import faye.engine.spi.HostRequest;
import faye.engine.spi.Redirect;
import faye.engine.spi.ScreenView;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADR 0027 in the app, on SQLite: versions are stored with their source map, older versions get one
 * at startup only when their YAML still compiles to their hash, failures are logged at the YAML
 * line, and the person signing in sees a reference and nothing about the flow.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SourceLinesTest {

    /** Serves every challenge as client {@code demo} of the default tenant; accepting throws while {@code down}. */
    static final class FlakyHost implements FlowHost {
        volatile boolean down;

        @Override
        public HostRequest load(HostKind kind, String ref) {
            return new HostRequest(kind, ref, null, false, Map.of("client_id", "demo"), HostParams.NONE);
        }

        @Override
        public Completion accept(HostRequest request, Authenticated outcome) {
            if (down) throw new IllegalStateException("the authorization server is down");
            return new Redirect("http://localhost/done");
        }

        @Override
        public Completion accept(HostRequest request, Consented outcome) {
            return new Redirect("http://localhost/done");
        }

        @Override
        public Completion reject(HostRequest request, String error, String description) {
            return new Redirect("http://localhost/refused");
        }
    }

    @TestConfiguration
    static class Flaky {
        @Bean
        @Primary
        FlakyHost flakyHost() {
            return new FlakyHost();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    Engine engine;

    @Autowired
    FlakyHost host;

    @Autowired
    FlowVersions versions;

    @Autowired
    FlowStore flows;

    @Autowired
    TenantConfigStore store;

    @Autowired
    SourceLines lines;

    @Autowired
    FailureLog log;

    @Autowired
    JdbcClient db;

    @Autowired
    FayeProperties props;

    @Autowired
    FlowCompiler.Options dsl;

    final JsonSchema protocol = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(FlowApiTest.read(FlowApiTest.ROOT.resolve("src/schema/screen.schema.json")));

    String current() {
        return versions.current("default").orElseThrow().hash();
    }

    @Test
    void publishStoresTheSourceMap() {
        assertThat(store.sourceMap(current()))
                .hasValueSatisfying(m -> assertThat(m.origin()).isEqualTo("password-only.yaml"));
        // credential (seq child 1), its block's retry, the retry's body, then its call: password.verify
        assertThat(lines.locate(current(), new Site("login", List.of(1, 0, 0, 1))))
                .hasValueSatisfying(at -> {
                    assertThat(at.toString()).isEqualTo("password-only.yaml:10:14");
                    assertThat(at.kind()).isEqualTo("use");
                });
        assertThat(lines.locate(current(), new Site("login", List.of(9)))).isEmpty();
        assertThat(lines.locate("sha256:unknown", new Site("login", List.of()))).isEmpty();
    }

    @Test
    void backfillSkipsAVersionWhoseYamlNoLongerCompilesToItsHash() {
        Map<String, Resource> configured = Map.of(
                "default",
                java.util.Objects.requireNonNull(props.tenants().get("default")).flow());
        String hash = current();
        String yaml = store.source(hash).orElseThrow();
        // Stored before ADR 0027: YAML, no map
        db.sql("DELETE FROM faye_version_source_map WHERE hash = ?").param(hash).update();
        assertThat(store.unmappedSources()).contains(hash);
        lines.backfill(configured, dsl);
        assertThat(store.sourceMap(hash)).isPresent();

        // YAML that now lowers to another version: no map, rather than one with the wrong lines
        db.sql("DELETE FROM faye_version_source_map WHERE hash = ?").param(hash).update();
        db.sql("UPDATE faye_version_source SET yaml = ? WHERE hash = ?")
                .params(yaml.replace("version: 1", "version: 2"), hash)
                .update();
        try {
            lines.backfill(configured, dsl);
            assertThat(store.sourceMap(hash)).isEmpty();
            assertThat(lines.locate(hash, new Site("login", List.of()))).isEmpty();
        } finally {
            db.sql("UPDATE faye_version_source SET yaml = ? WHERE hash = ?")
                    .params(yaml, hash)
                    .update();
            lines.backfill(configured, dsl);
        }
        assertThat(store.sourceMap(hash)).isPresent();
    }

    @Test
    void aHostErrorShowsAReferenceAndNoSource() throws Exception {
        String binder = Engine.newBinder();
        var identifier = (ScreenView) engine.start(HostKind.LOGIN, "flaky-1", Map.of(), binder);
        var password = (ScreenView) engine.submit(
                identifier.flowId(),
                binder,
                identifier.nonce(),
                new Submission(Map.of("identifier", "alice@example.com")));
        host.down = true;
        String body;
        try {
            body = mvc.perform(post("/api/flows/" + password.flowId() + "/submit")
                            .cookie(new Cookie(FlowController.COOKIE_PREFIX + password.flowId(), binder))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FlowApiTest.JSON.writeValueAsString(Map.of(
                                    "nonce",
                                    password.nonce(),
                                    "action",
                                    "submit",
                                    "payload",
                                    Map.of("password", "correct horse battery staple")))))
                    .andExpect(status().isBadGateway())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
        } finally {
            host.down = false;
        }
        JsonNode json = FlowApiTest.JSON.readTree(body);
        assertThat(protocol.validate(json)).as(body).isEmpty();
        assertThat(json.get("error").asText()).isEqualTo("host_error");
        assertThat(json.get("reference").asText()).matches(FailureSite.REFERENCE);
        // Nothing about the flow: no file, line, step or handler
        assertThat(json.size()).isEqualTo(2);
        assertThat(body).doesNotContain("yaml", "credential", "password.verify", "login");
    }

    @Test
    void theLogLineHoldsTheReferenceAndTheLine() {
        String binder = Engine.newBinder();
        var identifier = (ScreenView) engine.start(HostKind.LOGIN, "flaky-2", Map.of(), binder);
        var state = flows.load(identifier.flowId()).orElseThrow();
        var site = new FailureSite("login", List.of(1, 0, 0, 1), "credential", "hook_error", "ref_ABCDEFabcdef");
        String line = log.line(state, site);
        assertThat(line)
                .contains("code=hook_error")
                .contains("reference=ref_ABCDEFabcdef")
                .contains("source=password-only.yaml:10:14 kind=use")
                .contains("tenant=default")
                .contains("flow=" + identifier.flowId())
                .contains("version=" + current())
                .contains("step=credential")
                .contains("path=1.0.0.1");
        assertThat(FailureLog.level("hook_error")).isEqualTo(System.Logger.Level.WARNING);
        assertThat(FailureLog.level("invalid_credentials")).isEqualTo(System.Logger.Level.INFO);
    }
}
