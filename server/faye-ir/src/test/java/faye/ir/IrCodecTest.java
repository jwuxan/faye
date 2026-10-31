package faye.ir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class IrCodecTest {

    static final Path ROOT = Path.of(System.getProperty("faye.repoRoot"));
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String HASH = "sha256:" + "0".repeat(64);
    static final JsonSchema SCHEMA = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(read(ROOT.resolve("schema/ir.schema.json")));

    static Stream<Path> conformanceFlows() throws IOException {
        return Files.list(ROOT.resolve("conformance/flows")).sorted();
    }

    @ParameterizedTest
    @MethodSource("conformanceFlows")
    void conformanceFlowsMatchSchemaAndRoundTrip(Path file) throws IOException {
        var json = MAPPER.readTree(read(file));
        assertThat(SCHEMA.validate(json)).isEmpty();

        Flow flow = IrCodec.readFlow(json);
        assertThat(IrCodec.toJson(flow)).isEqualTo(json);
        assertThat(SCHEMA.validate(MAPPER.readTree(IrCodec.canonicalJson(flow))))
                .isEmpty();
    }

    @Test
    void canonicalFormSortsKeysAndIsWhitespaceFree() {
        Flow flow = IrCodec.readFlow("""
                ["flow", "f", 1,
                  ["login", ["seq", ["screen", "identifier", {"b": 1, "a": true}], ["call", "x.y", {}], ["succeed"]]]]
                """);
        assertThat(IrCodec.canonicalJson(flow))
                .isEqualTo("[\"flow\",\"f\",1,[\"login\",[\"seq\",[\"screen\",\"identifier\",{\"a\":true,\"b\":1}],"
                        + "[\"call\",\"x.y\"],[\"succeed\"]]]]");
    }

    @Test
    void hashIgnoresFormattingAndSourceRefs() {
        Flow compact = IrCodec.readFlow("[\"flow\",\"f\",1,[\"login\",[\"succeed\"]]]");
        Flow spaced = IrCodec.readFlow("[ \"flow\", \"f\", 1, [ \"login\", [ \"succeed\" ] ] ]");
        Flow sourced = new Flow("f", 1, java.util.Map.of("login", new Succeed(new SourceRef("f.yaml", 3, 1))));
        assertThat(IrCodec.hash(compact))
                .startsWith("sha256:")
                .hasSize(7 + 64)
                .isEqualTo(IrCodec.hash(spaced))
                .isEqualTo(IrCodec.hash(sourced));
        assertThat(IrCodec.hash(IrCodec.readFlow("[\"flow\",\"f\",2,[\"login\",[\"succeed\"]]]")))
                .isNotEqualTo(IrCodec.hash(compact));
    }

    @Test
    void rejectsMalformedIrWithPointer() {
        List.of(
                        "[\"flow\",\"f\",1,[\"login\",[\"loop\"]]]",
                        "[\"flow\",\"f\",1,[\"login\",[\"step\",\"Bad Id\",[\"succeed\"]]]]",
                        "[\"flow\",\"f\",1,[\"login\",[\"succeed\",1]]]",
                        "[\"flow\",\"f\",1,[\"login\",[\"seq\"]]]",
                        "[\"flow\",\"f\",1,[\"admin\",[\"succeed\"]]]",
                        "[\"flow\",\"f\",1,[\"login\",[\"screen\",\"password\",{\"a\":1.5}]]]",
                        "[\"flow\",\"f\",0,[\"login\",[\"succeed\"]]]",
                        "[\"flow\",\"f\",1,[\"login\",[\"retry\",0,[\"succeed\"]]]]",
                        "[\"flow\",\"f\",1,[\"login\",[\"retry\",11,[\"succeed\"]]]]",
                        "[\"flow\",\"f\",1,[\"login\",[\"choose\"]]]",
                        "[\"flow\",\"f\",1,[\"login\",[\"choose\",[\"succeed\"]]]]",
                        "[\"flow\",\"f\",1,[\"login\",[\"when\",\"user.x\"]]]",
                        "[\"flow\",\"f\",1,[\"login\",[\"when\",\"secrets.x\",[\"succeed\"]]]]",
                        "[\"flow\",\"f\",1,[\"login\",[\"when\",{\"xor\":[]},[\"succeed\"]]]]",
                        "[\"flow\",\"f\",1,[\"login\",[\"when\",{\"gt\":[\"user.n\",\"a\"]},[\"succeed\"]]]]",
                        "[\"flow\",\"f\",1,[\"login\",[\"when\",{\"and\":[]},[\"succeed\"]]]]",
                        "[\"flow\",\"f\",1,[\"login\",[\"set\",\"vars.x\",1]]]",
                        // A hook needs an id and a sha256 hash; in a choose it comes first, before an option
                        "[\"flow\",\"f\",1,[\"login\",[\"hook\",\"risk\"]]]",
                        "[\"flow\",\"f\",1,[\"login\",[\"hook\",\"risk\",\"md5:abc\"]]]",
                        "[\"flow\",\"f\",1,[\"login\",[\"choose\",[\"hook\",\"r\",\"" + HASH + "\"]]]]",
                        "[\"flow\",\"f\",1,[\"login\",[\"choose\",[\"opt\",\"a\",[\"succeed\"]]," + "[\"hook\",\"r\",\""
                                + HASH + "\"]]]]")
                .forEach(bad -> {
                    assertThatThrownBy(() -> IrCodec.readFlow(bad)).isInstanceOf(IrException.class);
                    try {
                        assertThat(SCHEMA.validate(MAPPER.readTree(bad)))
                                .as(bad)
                                .isNotEmpty();
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                });
        assertThatThrownBy(() -> IrCodec.readFlow("[\"flow\",\"f\",1,[\"login\",[\"seq\",[\"succeed\"],[\"nope\"]]]]"))
                .isInstanceOfSatisfying(
                        IrException.class, e -> assertThat(e.at()).isEqualTo("/3/1/2/0"));
    }

    @Test
    void duplicateOptionIdsAreRejected() {
        assertThatThrownBy(() -> IrCodec.readFlow("[\"flow\",\"f\",1,[\"login\",[\"choose\","
                        + "[\"opt\",\"a\",[\"succeed\"]],[\"opt\",\"a\",[\"succeed\"]]]]]"))
                .isInstanceOfSatisfying(
                        IrException.class, e -> assertThat(e.at()).isEqualTo("/3/1/2/1"));
    }

    @Test
    void predicatesRoundTrip() {
        String json = "[\"flow\",\"f\",1,[\"login\",[\"when\",{\"and\":[\"user.a\",{\"not\":{\"exists\":\"vars.b\"}},"
                + "{\"or\":[{\"eq\":[\"user.c\",\"x\"]},{\"ne\":[\"user.c\",true]},{\"gt\":[\"user.n\",1]},"
                + "{\"lt\":[\"user.n\",9]},{\"has\":[\"hydra.acr_values\",\"faye:2fa\"]}]}]},"
                + "[\"set\",\"risk\",\"high\"]]]]";
        assertThat(IrCodec.canonicalJson(IrCodec.readFlow(json))).isEqualTo(json);
    }

    static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
