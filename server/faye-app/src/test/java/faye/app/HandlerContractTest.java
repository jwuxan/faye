package faye.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import faye.engine.Handler;
import faye.engine.HandlerMeta;
import faye.engine.HandlerRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ADR 0024: {@code conformance/handlers.yaml} is the handler contract. The handlers this server
 * registers are exactly the ones it lists, each declaring exactly its metadata, so the checker and
 * the conformance fakes reason about the handlers that really run.
 */
@SpringBootTest
class HandlerContractTest {

    @Autowired
    HandlerRegistry handlers;

    @Test
    void productionHandlersAreTheContract() throws IOException {
        JsonNode contract = new YAMLMapper()
                .readTree(Path.of(System.getProperty("faye.repoRoot"), "conformance/handlers.yaml")
                        .toFile());
        Map<String, HandlerMeta> expected = new TreeMap<>();
        contract.properties().forEach(e -> expected.put(e.getKey(), HandlerMeta.fromYaml(e.getValue())));
        Map<String, HandlerMeta> actual = handlers.all().stream()
                .collect(Collectors.toMap(Handler::name, Handler::meta, (a, b) -> a, TreeMap::new));

        assertThat(new TreeSet<>(actual.keySet()))
                .as("registered handlers")
                .isEqualTo(new TreeSet<>(expected.keySet()));
        expected.forEach((name, meta) -> assertThat(actual.get(name)).as(name).isEqualTo(meta));
    }
}
