package faye.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import faye.engine.spi.CodeOutbox.Channel;
import faye.engine.spi.HostKind;
import faye.engine.spi.HostParams;
import faye.engine.testing.MockFlowHost;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * ADR 0032 through the screen API: a password, then a code emailed through the outbox, with resend
 * as a screen action; codes and destinations never reach the screen; the cross flow limits hold.
 */
@SpringBootTest(
        properties = {
            "faye.tenants.codes.flow=classpath:flows/email-code.yaml",
            "faye.demo-outbox=build/outbox-test",
        })
@AutoConfigureMockMvc
class DeliveredCodesApiTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path OUTBOX = Path.of("build/outbox-test");

    @Autowired
    MockMvc mvc;

    @Autowired
    MockFlowHost host;

    @Autowired
    JdbcClient db;

    @Autowired
    SqliteCodeOutbox outbox;

    @Autowired
    Tenants tenants;

    final JsonSchema protocol = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(FlowApiTest.read(FlowApiTest.ROOT.resolve("src/schema/screen.schema.json")));

    @Test
    void signsInWithAnEmailedCode() throws Exception {
        Files.deleteIfExists(OUTBOX.resolve("email-bob@example.com.json"));
        String flowId = login("c-email");
        JsonNode identifier = call(get("/api/flows/" + flowId), status().isOk());
        JsonNode password =
                send(flowId, identifier, "submit", Map.of("identifier", "bob@example.com"), status().isOk());
        JsonNode code =
                send(flowId, password, "submit", Map.of("password", "correct horse battery staple"), status().isOk());
        assertThat(code.at("/screen/type").asText()).isEqualTo("code_sent");
        assertThat(code.at("/screen/channel").asText()).isEqualTo("email");
        assertThat(code.at("/screen/to").asText()).isEqualTo("b•••@example.com");
        assertThat(code.at("/screen/resendIn").asInt()).isBetween(29, 30);
        assertThat(code.at("/screen/expiresIn").asInt()).isBetween(299, 300);

        String sent = delivered("email-bob@example.com.json");
        assertThat(code.toString()).doesNotContain(sent);
        // The worker cleared the code from the outbox once it was delivered
        assertThat(db.sql("SELECT count(*) FROM faye_outbox WHERE code IS NOT NULL")
                        .query(Long.class)
                        .single())
                .isZero();

        // Too soon to resend: the same screen, with send_limited; a screen action nothing declares is refused
        JsonNode limited = send(flowId, code, "resend", Map.of(), status().isOk());
        assertThat(limited.at("/screen/errors/0/code").asText()).isEqualTo("send_limited");
        assertThat(limited.get("nonce").asText()).isEqualTo(code.get("nonce").asText());
        JsonNode unknown = send(flowId, code, "poll", Map.of(), status().isBadRequest());
        assertThat(unknown.at("/error").asText()).isEqualTo("unknown_action");
        assertThat(protocol.validate(unknown)).isEmpty();

        JsonNode wrong = send(flowId, code, "submit", Map.of("code", other(sent)), status().isOk());
        assertThat(wrong.at("/screen/errors/0/code").asText()).isEqualTo("invalid_code");
        assertThat(wrong.at("/screen/attemptsLeft").asInt()).isEqualTo(2);
        JsonNode done = send(flowId, wrong, "submit", Map.of("code", sent), status().isOk());
        assertThat(done.has("redirectTo")).isTrue();
        assertThat(host.accepted()).anySatisfy(a -> {
            assertThat(a.outcome().subject()).isEqualTo("user-bob");
            assertThat(a.outcome().amr()).containsExactly("pwd", "email");
            assertThat(a.outcome().acr()).isEqualTo("faye:1fa");
        });
    }

    /** An unknown account gets the same screen; its outbox row is never delivered. */
    @Test
    void anUnknownAccountGetsTheSameScreen() throws Exception {
        String flowId = login("c-unknown");
        JsonNode identifier = call(get("/api/flows/" + flowId), status().isOk());
        JsonNode password =
                send(flowId, identifier, "submit", Map.of("identifier", "nobody@example.com"), status().isOk());
        JsonNode code = send(flowId, password, "submit", Map.of("password", "guess"), status().isOk());
        // The password failed, and an unknown account is retried like a known one
        assertThat(code.at("/screen/type").asText()).isEqualTo("password");
        assertThat(db.sql("SELECT count(*) FROM faye_outbox WHERE limit_key = 'nobody@example.com'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void theLimitsSpanFlows() {
        String key = "limit-" + System.nanoTime() + "@example.com";
        int accepted = 0;
        for (int i = 0; i < 8; i++) {
            if (outbox.enqueue("codes", Channel.EMAIL, null, key, "123456")) accepted++;
        }
        assertThat(accepted).isEqualTo(SqliteCodeOutbox.PER_DESTINATION_HOUR);
    }

    /** With the demo outbox set every channel delivers, so no handler is taken away. */
    @Test
    void everyChannelIsOnWithTheDemoOutbox() {
        assertThat(tenants.get("codes").handlers()).isNull();
    }

    @Test
    void aChannelWithoutDeliveryTakesItsHandlersAway() {
        var props = new FayeProperties(
                "http://localhost:3000",
                java.time.Duration.ofMinutes(30),
                Map.of(
                        "plain",
                        new FayeProperties.Tenant(
                                new org.springframework.core.io.ByteArrayResource(new byte[0]),
                                false,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null),
                        "mail",
                        new FayeProperties.Tenant(
                                new org.springframework.core.io.ByteArrayResource(new byte[0]),
                                false,
                                null,
                                null,
                                null,
                                null,
                                null,
                                new FayeProperties.Delivery(
                                        new FayeProperties.Webhook("https://mail.example/hook", "s", null), null))),
                java.util.List.of(),
                "",
                new FayeProperties.Webauthn("localhost", Set.of()),
                "",
                "",
                Map.of(),
                false,
                null,
                null);
        var delivery = new CodeDelivery(props, java.time.Clock.systemUTC());
        assertThat(delivery.unavailable("plain"))
                .containsExactlyInAnyOrder("email_code.verify", "sms_code.verify", "phone.enroll");
        assertThat(delivery.unavailable("mail")).containsExactlyInAnyOrder("sms_code.verify", "phone.enroll");
        var narrowed = new Tenants(
                props,
                new InMemoryTenantConfigStore(),
                Set.of("password.verify", "email_code.verify", "sms_code.verify", "phone.enroll"),
                delivery::unavailable);
        assertThat(narrowed.get("mail").handlers()).containsExactlyInAnyOrder("password.verify", "email_code.verify");
        assertThat(narrowed.undeliverable("mail")).containsExactlyInAnyOrder("sms_code.verify", "phone.enroll");
    }

    @Test
    void aWebhookIsSignedOverItsBody() {
        byte[] body = CodeDelivery.body(new CodeDelivery.Message("codes", Channel.SMS, "+15551234821", "123456"));
        assertThat(new String(body, java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("{\"channel\":\"sms\",\"to\":\"+15551234821\",\"code\":\"123456\",\"tenant\":\"codes\"}");
        assertThat(CodeDelivery.sign("secret", 1700000000L, body))
                .hasSize(64)
                .isNotEqualTo(CodeDelivery.sign("other", 1700000000L, body))
                .isNotEqualTo(CodeDelivery.sign("secret", 1700000001L, body));
    }

    String login(String challenge) throws Exception {
        host.register(MockFlowHost.request(
                HostKind.LOGIN,
                challenge,
                Map.of("client_id", "demo", "metadata", Map.of("tenant", "codes")),
                HostParams.fromMap(Map.of())));
        String location = mvc.perform(get("/login").param("login_challenge", challenge))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        return FlowApiTest.flowId(location);
    }

    /** The code the outbox delivered, once the worker wrote it. */
    static String delivered(String file) throws Exception {
        Path path = OUTBOX.resolve(file);
        for (int i = 0; i < 50 && !Files.exists(path); i++) Thread.sleep(100);
        return JSON.readTree(path.toFile()).get("code").asText();
    }

    static String other(String code) {
        return "000000".equals(code) ? "000001" : "000000";
    }

    JsonNode send(String flowId, JsonNode screen, String action, Map<String, Object> payload, ResultMatcher expected)
            throws Exception {
        var body = Map.of("nonce", screen.get("nonce").asText(), "action", action, "payload", payload);
        JsonNode out = call(
                post("/api/flows/" + flowId + "/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)),
                expected);
        if (out.has("screen")) {
            assertThat(protocol.validate(out)).as(out.toString()).isEmpty();
        }
        return out;
    }

    JsonNode call(org.springframework.test.web.servlet.RequestBuilder request, ResultMatcher expected)
            throws Exception {
        String body = mvc.perform(request)
                .andExpect(expected)
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JSON.readTree(body);
    }
}
