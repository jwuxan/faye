package faye.host.hydra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import faye.engine.ClientDirectory.Client;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Lists clients from a fake Hydra admin API that serves them in two pages. */
class HydraClientDirectoryTest {

    HttpServer server;
    HydraClientDirectory directory;
    final List<String> methods = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    /** When set every page links to a next one, to check the page cap. */
    volatile boolean endless = false;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/admin/clients", exchange -> {
            methods.add(exchange.getRequestMethod());
            boolean second = !endless
                    && String.valueOf(exchange.getRequestURI().getQuery()).contains("page_token=two");
            String body = second ? """
                      [{"client_id":"partner-app","client_name":"Partner app",
                        "token_endpoint_auth_method":"client_secret_basic","redirect_uris":["https://partner.test/cb"],"grant_types":["authorization_code"],
                        "metadata":{"tenant":"partner","first_party":false}}]
                      """ : """
                      [{"client_id":"demo","client_name":"Faye demo client","token_endpoint_auth_method":"none",
                        "redirect_uris":["http://localhost:3001/callback"],
                        "grant_types":["authorization_code","refresh_token"],
                        "created_at":"2026-10-31T00:00:00Z",
                        "metadata":{"tenant":"default","first_party":true,"preview":false}},
                       {"client_id":"no-tenant","client_secret":"never-listed"}]
                      """;
            if (endless || !second) {
                exchange.getResponseHeaders()
                        .add("Link", "</admin/clients?page_size=500&page_token=two>; rel=\"next\"");
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        directory = new HydraClientDirectory(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void followsPagesAndReadsTenantAndPartyFromMetadata() {
        List<Client> clients = directory.clients();
        assertThat(clients).extracting(Client::id).containsExactly("demo", "no-tenant", "partner-app");

        Client demo = clients.get(0);
        assertThat(demo.tenant()).isEqualTo("default");
        assertThat(demo.firstParty()).isTrue();
        assertThat(demo.authMethod()).isEqualTo("none");
        assertThat(demo.redirectUris()).containsExactly("http://localhost:3001/callback");
        assertThat(demo.createdAt()).isEqualTo("2026-10-31T00:00:00Z");

        Client bare = clients.get(1);
        assertThat(bare.tenant()).isNull();
        assertThat(bare.authMethod()).isEqualTo("client_secret_basic");
        assertThat(bare.toString()).doesNotContain("never-listed");

        assertThat(clients.get(2).firstParty()).isFalse();
        // Only reads
        assertThat(methods).containsOnly("GET");
    }

    @Test
    void stopsAfterTwentyPages() {
        endless = true;
        // Twenty pages of two clients each: the page cap stops the walk
        assertThat(directory.clients()).hasSize(40);
        assertThat(methods).hasSize(20);
    }

    @Test
    void anErrorFromHydraIsReported() {
        status = 503;
        assertThatThrownBy(directory::clients)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("503");
    }
}
