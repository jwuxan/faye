package faye.handlers;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import faye.engine.Handler;
import faye.engine.HandlerMeta;
import faye.engine.HandlerRegistry;
import faye.engine.testing.InMemoryCredentials;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * ADR 0024 / ADR 0030: {@code conformance/handlers.yaml} is the handler contract — the handlers the
 * engine ships are exactly the ones it lists, each declaring exactly its metadata, so the checker
 * and the conformance fakes reason about the handlers that really run.
 */
class BuiltinContractTest {

    private static final Path CONFORMANCE = Path.of(System.getProperty("faye.repoRoot"), "src/conformance");

    @Test
    void builtinHandlersAreTheContract() throws IOException {
        JsonNode contract =
                new YAMLMapper().readTree(CONFORMANCE.resolve("handlers.yaml").toFile());
        Map<String, HandlerMeta> expected = new TreeMap<>();
        contract.properties().forEach(e -> expected.put(e.getKey(), HandlerMeta.fromYaml(e.getValue())));
        var creds = new InMemoryCredentials();
        List<Handler> all = new ArrayList<>(BuiltinHandlers.create(creds, creds, creds, creds));
        all.addAll(DeliveredCodes.handlers(creds, creds, new byte[32], Clock.systemUTC()));
        all.addAll(QrSignIn.handlers(new faye.engine.testing.InMemoryQrGrants(), creds, Clock.systemUTC()));
        Map<String, HandlerMeta> actual = new HandlerRegistry(all)
                .all().stream().collect(Collectors.toMap(Handler::name, Handler::meta, (a, b) -> a, TreeMap::new));

        assertThat(new TreeSet<>(actual.keySet()))
                .as("registered handlers")
                .isEqualTo(new TreeSet<>(expected.keySet()));
        expected.forEach((name, meta) -> assertThat(actual.get(name)).as(name).isEqualTo(meta));
    }
}
