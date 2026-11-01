package faye.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * ADR 0028: through a real Tomcat, the admin API answers on the Unix domain socket and only there,
 * and the socket serves nothing else.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"faye.admin-token=test-token", "faye.admin-socket=build/admin-${random.uuid}.sock"})
class AdminSocketTest {

    @LocalServerPort
    int port;

    @Autowired
    FayeProperties props;

    @Test
    void theAdminApiAnswersOnTheSocket() throws Exception {
        assertThat(overSocket("/admin/tenants", "Bearer test-token"))
                .startsWith("HTTP/1.1 200 ")
                .contains("\"id\":\"default\"");
        assertThat(overSocket("/admin/tenants", "Bearer wrong")).startsWith("HTTP/1.1 401 ");
        assertThat(Files.getPosixFilePermissions(Path.of(props.adminSocket())))
                .isEqualTo(PosixFilePermissions.fromString(AdminSocket.PERMISSIONS));
    }

    @Test
    void theAdminApiIsNotOnTheTcpPort() throws Exception {
        for (String path : new String[] {
            "/admin/tenants",
            "/admin",
            "/api/../admin/tenants",
            "//admin/tenants",
            "/admin;x/tenants",
            "/%61dmin/tenants"
        }) {
            assertThat(overTcp(path)).as(path).isEqualTo(404);
        }
        assertThat(overTcp("/api/flows/unknown")).isEqualTo(404);
        assertThat(overTcp("/login")).isNotEqualTo(404);
    }

    @Test
    void theSocketServesNothingButTheAdminApi() throws Exception {
        assertThat(overSocket("/login", "Bearer test-token")).startsWith("HTTP/1.1 404 ");
        assertThat(overSocket("/api/preview/flows/unknown", "Bearer test-token"))
                .startsWith("HTTP/1.1 404 ");
        assertThat(overSocket("/administrator", "Bearer test-token")).startsWith("HTTP/1.1 404 ");
    }

    int overTcp(String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Authorization", "Bearer test-token")
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    /** The whole response to a GET over the socket, status line first. */
    String overSocket(String path, String authorization) throws Exception {
        try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(props.adminSocket()))) {
            String request = "GET " + path + " HTTP/1.1\r\nHost: localhost\r\nAuthorization: " + authorization
                    + "\r\nConnection: close\r\n\r\n";
            channel.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.US_ASCII)));
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) >= 0) {
                response.write(buffer.array(), 0, buffer.position());
                buffer.clear();
            }
            return response.toString(StandardCharsets.UTF_8);
        }
    }
}
