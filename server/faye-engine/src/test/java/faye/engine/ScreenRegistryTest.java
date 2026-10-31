package faye.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The built in screens are exactly the screen types of the screen protocol. */
class ScreenRegistryTest {

    @Test
    void builtInScreensAreTheProtocolsScreens() throws IOException {
        JsonNode schema = new ObjectMapper()
                .readTree(Path.of(System.getProperty("faye.repoRoot"), "schema/screen.schema.json")
                        .toFile());
        Set<String> protocol = new TreeSet<>();
        for (JsonNode ref : schema.at("/$defs/Screen/oneOf")) {
            String def = ref.get("$ref").asText().substring("#/$defs/".length());
            protocol.add(schema.at("/$defs/" + def + "/properties/type/const").asText());
        }
        Set<String> registered = ScreenRegistry.builtIn().all().stream()
                .map(ScreenSpec::type)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(registered).isEqualTo(protocol).contains(ScreenRegistry.METHOD_PICKER);
    }
}
