package faye.app;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * Serves the admin API over HTTP on a Unix domain socket ({@code faye.admin-socket}) and nowhere else
 * (ADR 0028): whoever can open the socket file can reach it, so the file's directory decides who
 * that is, and no network peer ever can. Requests are split by the connector they arrive on, before
 * any servlet sees them: {@code /admin} and below only on the socket, everything else only on the
 * TCP port; the other way round is a 404 with no body. With no socket configured, the admin API is
 * not served at all. The bearer token ({@link AdminAuth}) is still checked on the socket.
 */
@Component
final class AdminSocket implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    /** Owner and group may connect; the directory it is in narrows that further. */
    static final String PERMISSIONS = "rw-rw----";

    private final FayeProperties props;

    AdminSocket(FayeProperties props) {
        this.props = props;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        @Nullable Connector socket = null;
        if (!props.adminSocket().isEmpty()) {
            // A socket left by a process that did not shut down would make the bind fail
            try {
                Files.deleteIfExists(Path.of(props.adminSocket()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            socket = new Connector();
            socket.setProperty("unixDomainSocketPath", props.adminSocket());
            socket.setProperty("unixDomainSocketPathPermissions", PERMISSIONS);
            factory.addAdditionalTomcatConnectors(socket);
        }
        factory.addEngineValves(new Split(socket));
    }

    /** {@code /admin}, or a path below it, after Tomcat decoded and normalized it. */
    static boolean admin(String path) {
        return "/admin".equals(path) || path.startsWith("/admin/");
    }

    /** Lets an admin path through only from the socket, and any other path only from TCP. */
    private static final class Split extends ValveBase {
        private final @Nullable Connector socket;

        Split(@Nullable Connector socket) {
            // The preview trace streams over an async request
            super(true);
            this.socket = socket;
        }

        @Override
        public void invoke(Request request, Response response) throws IOException, ServletException {
            boolean fromSocket = socket != null && request.getConnector() == socket;
            if (admin(request.getDecodedRequestURI()) != fromSocket) {
                response.sendError(Response.SC_NOT_FOUND);
                return;
            }
            getNext().invoke(request, response);
        }
    }
}
