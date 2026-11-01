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
import faye.engine.spi.HostKind;
import faye.engine.spi.HostParams;
import faye.engine.testing.MockFlowHost;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * Acme's shipped flow (ADR 0032): it passes the checker at startup with every new block, and a QR
 * code sign in runs end to end through the screen API, the phone's approve flow started at {@code
 * /api/approve/{token}}. The risk hook's service is not configured here, so every sign in scores
 * high and asks for a second factor.
 */
@SpringBootTest(
        properties = {
            "faye.tenants.acme.flow=classpath:flows/acme.yaml",
            "faye.tenants.acme.egress=ip-reputation",
            "faye.demo-outbox=build/outbox-acme",
        })
@AutoConfigureMockMvc
class AcmeFlowTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;

    @Autowired
    MockFlowHost host;

    final JsonSchema protocol = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(FlowApiTest.read(FlowApiTest.ROOT.resolve("src/schema/screen.schema.json")));

    @Test
    void aQrCodeApprovedOnAPhoneSignsInTheWaitingDevice() throws Exception {
        host.register(MockFlowHost.request(
                HostKind.LOGIN,
                "c-acme-qr",
                Map.of("client_id", "web", "client_name", "Acme Web", "metadata", Map.of("tenant", "acme")),
                HostParams.fromMap(Map.of())));
        String location = mvc.perform(get("/login").param("login_challenge", "c-acme-qr"))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        String waiting = FlowApiTest.flowId(location);
        JsonNode screen = call(get("/api/flows/" + waiting), status().isOk());
        screen = send(waiting, screen, "submit", Map.of("identifier", "alice@example.com"));
        assertThat(screen.at("/screen/type").asText()).isEqualTo("method_picker");
        assertThat(screen.at("/screen/options").toString()).isEqualTo("[\"password\",\"email\",\"qr\"]");
        JsonNode qr = send(waiting, screen, "submit", Map.of("method", "qr"));
        assertThat(qr.at("/screen/type").asText()).isEqualTo("qr");
        assertThat(qr.at("/screen/status").asText()).isEqualTo("pending");
        String url = qr.at("/screen/url").asText();
        assertThat(url).startsWith("http://localhost:3000/approve/");
        long number = qr.at("/screen/number").asLong();

        // The phone: Faye's own request, for the waiting device's tenant and client
        String token = url.substring(url.lastIndexOf('/') + 1);
        JsonNode started = call(post("/api/approve/" + token), status().isOk());
        String phone = FlowApiTest.flowId("http://x" + started.get("location").asText());
        JsonNode p = call(get("/api/flows/" + phone), status().isOk());
        assertThat(p.at("/screen/type").asText()).isEqualTo("identifier");
        p = send(phone, p, "submit", Map.of("identifier", "alice@example.com"));
        assertThat(p.at("/screen/type").asText()).isEqualTo("password");
        p = send(phone, p, "submit", Map.of("password", "correct horse battery staple"));
        assertThat(p.at("/screen/type").asText()).isEqualTo("qr_approve");
        assertThat(p.at("/screen/client/name").asText()).isEqualTo("Acme Web");
        assertThat(p.at("/screen/numbers").toString()).contains(String.valueOf(number));
        JsonNode done = send(phone, p, "submit", Map.of("number", number));
        assertThat(done.at("/result/qr").asText()).isEqualTo("approved");
        // A QR code approves once
        call(post("/api/approve/" + token), status().isNotFound());

        JsonNode polled = send(waiting, qr, "poll", Map.of());
        assertThat(polled.at("/screen/status").asText()).isEqualTo("approved");
        JsonNode second = send(waiting, polled, "submit", Map.of());
        // High risk without the service: Alice's authenticator app as the second factor
        assertThat(second.at("/screen/type").asText()).isEqualTo("otp");
        JsonNode addPhone = send(waiting, second, "submit", Map.of("code", totp("JBSWY3DPEHPK3PXP")));
        assertThat(addPhone.at("/screen/type").asText()).isEqualTo("phone_enroll");
        JsonNode finished = send(waiting, addPhone, "submit", Map.of("skip", true));
        assertThat(finished.has("redirectTo")).isTrue();
        assertThat(host.accepted()).anySatisfy(a -> {
            assertThat(a.request().ref()).isEqualTo("c-acme-qr");
            assertThat(a.outcome().subject()).isEqualTo("user-alice");
            assertThat(a.outcome().amr()).isEqualTo(List.of("pwd", "otp"));
        });
    }

    /**
     * An emailed code and a texted code each stand in for the password: Bob signs in with a code
     * emailed to him, adds a phone on the way out, then signs in again with a code texted to it.
     */
    @Test
    void anEmailedOrTextedCodeStandsInForThePassword() throws Exception {
        java.nio.file.Path outbox = java.nio.file.Path.of("build/outbox-acme");
        java.nio.file.Files.deleteIfExists(outbox.resolve("email-bob@example.com.json"));
        java.nio.file.Files.deleteIfExists(outbox.resolve("sms-+15551234821.json"));

        String first = acmeLogin("c-acme-email");
        JsonNode screen = call(get("/api/flows/" + first), status().isOk());
        screen = send(first, screen, "submit", Map.of("identifier", "bob@example.com"));
        assertThat(screen.at("/screen/options").toString()).isEqualTo("[\"password\",\"email\",\"qr\"]");
        JsonNode emailed = send(first, screen, "submit", Map.of("method", "email"));
        assertThat(emailed.at("/screen/type").asText()).isEqualTo("code_sent");
        assertThat(emailed.at("/screen/channel").asText()).isEqualTo("email");
        // High risk without the service, and Bob has no authenticator app: enroll one
        JsonNode enroll =
                send(first, emailed, "submit", Map.of("code", delivered(outbox, "email-bob@example.com.json")));
        assertThat(enroll.at("/screen/type").asText()).isEqualTo("otp_enroll");
        String secret = enroll.at("/screen/secret").asText();
        JsonNode addPhone = send(first, enroll, "submit", Map.of("code", totp(secret)));
        assertThat(addPhone.at("/screen/type").asText()).isEqualTo("phone_enroll");
        addPhone = send(first, addPhone, "send", Map.of("phone", "+15551234821"));
        JsonNode finished = send(first, addPhone, "submit", Map.of("code", delivered(outbox, "sms-+15551234821.json")));
        assertThat(finished.has("redirectTo")).as(finished.toString()).isTrue();
        assertThat(host.accepted()).anySatisfy(a -> {
            assertThat(a.request().ref()).isEqualTo("c-acme-email");
            assertThat(a.outcome().subject()).isEqualTo("user-bob");
            assertThat(a.outcome().amr()).containsExactlyInAnyOrder("email", "otp");
        });

        java.nio.file.Files.deleteIfExists(outbox.resolve("sms-+15551234821.json"));
        String second = acmeLogin("c-acme-sms");
        screen = call(get("/api/flows/" + second), status().isOk());
        screen = send(second, screen, "submit", Map.of("identifier", "bob@example.com"));
        assertThat(screen.at("/screen/options").toString()).isEqualTo("[\"password\",\"email\",\"phone\",\"qr\"]");
        JsonNode texted = send(second, screen, "submit", Map.of("method", "phone"));
        assertThat(texted.at("/screen/type").asText()).isEqualTo("code_sent");
        assertThat(texted.at("/screen/channel").asText()).isEqualTo("sms");
        JsonNode otp = send(second, texted, "submit", Map.of("code", delivered(outbox, "sms-+15551234821.json")));
        assertThat(otp.at("/screen/type").asText()).isEqualTo("otp");
        // The next step's code: the current one was spent enrolling, and a code is accepted once
        JsonNode done = send(second, otp, "submit", Map.of("code", totp(secret, 1)));
        // Bob has a phone now, so no offer to add one
        assertThat(done.has("redirectTo")).as(done.toString()).isTrue();
        assertThat(host.accepted()).anySatisfy(a -> {
            assertThat(a.request().ref()).isEqualTo("c-acme-sms");
            assertThat(a.outcome().subject()).isEqualTo("user-bob");
            assertThat(a.outcome().amr()).containsExactlyInAnyOrder("sms", "otp");
        });
    }

    String acmeLogin(String challenge) throws Exception {
        host.register(MockFlowHost.request(
                HostKind.LOGIN,
                challenge,
                Map.of("client_id", "web", "client_name", "Acme Web", "metadata", Map.of("tenant", "acme")),
                HostParams.fromMap(Map.of())));
        String location = mvc.perform(get("/login").param("login_challenge", challenge))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        return FlowApiTest.flowId(location);
    }

    /** The code the demo outbox delivered, once the worker wrote it. */
    static String delivered(java.nio.file.Path outbox, String file) throws Exception {
        java.nio.file.Path path = outbox.resolve(file);
        for (int i = 0; i < 50 && !java.nio.file.Files.exists(path); i++) Thread.sleep(100);
        return JSON.readTree(path.toFile()).get("code").asText();
    }

    @Test
    void anUnknownQrCodeStartsNothing() throws Exception {
        JsonNode refused = call(post("/api/approve/not-a-token"), status().isNotFound());
        assertThat(refused.at("/error").asText()).isEqualTo("flow_not_found");
    }

    JsonNode send(String flowId, JsonNode screen, String action, Map<String, Object> payload) throws Exception {
        var body = Map.of("nonce", screen.get("nonce").asText(), "action", action, "payload", payload);
        JsonNode out = call(
                post("/api/flows/" + flowId + "/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)),
                status().isOk());
        assertThat(protocol.validate(out)).as(out.toString()).isEmpty();
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

    /** The current RFC 6238 code of a base32 secret. */
    static String totp(String base32) throws Exception {
        return totp(base32, 0);
    }

    /** The RFC 6238 code of a base32 secret, {@code offset} steps from now. */
    static String totp(String base32, int offset) throws Exception {
        byte[] key = base32(base32);
        long step = System.currentTimeMillis() / 1000 / 30 + offset;
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
        int o = h[h.length - 1] & 0xf;
        int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
        return String.format("%06d", bin % 1_000_000);
    }

    private static byte[] base32(String s) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        ByteBuffer out = ByteBuffer.allocate(s.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : s.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.put((byte) (buffer >> (bits - 8)));
                bits -= 8;
            }
        }
        return out.array();
    }
}
