// A stand in for an IP reputation service, for the risk hook demo (tenant risk). Not a real
// scorer: GET /score?ip=...&user_agent=... answers {"score": 90} when the user agent contains
// "suspicious" or the IP is in 203.0.113.0/24 (TEST-NET-3, what the Admin UI's "High risk IP"
// preview uses), else {"score": 10}. Runs as a single source file: java IpReputation.java
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class IpReputation {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
        server.createContext("/score", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            String decoded = query == null ? "" : URLDecoder.decode(query, StandardCharsets.UTF_8);
            boolean risky = decoded.toLowerCase().contains("suspicious") || decoded.contains("ip=203.0.113.");
            int score = risky ? 90 : 10;
            byte[] body = ("{\"score\":" + score + "}").getBytes(StandardCharsets.UTF_8);
            System.out.println("score " + score + " for " + decoded);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        System.out.println("ip-reputation stub on :8000");
    }
}
