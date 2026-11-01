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
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class IrCodecTest {

    static final Path ROOT = Path.of(System.getProperty("faye.repoRoot"));
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String HASH = "sha256:" + "0".repeat(64);
    static final JsonSchema SCHEMA = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(read(ROOT.resolve("src/schema/ir.schema.json")));

    /** A format 1 flow with one authenticate entry around {@code body}. */
    static String login(String body) {
        return "[\"flow\",1,\"f\",1,[\"entry\",\"login\",\"authenticate\"," + body + "]]";
    }

    static List<Path> conformanceFlows() throws IOException {
        try (Stream<Path> files = Files.list(ROOT.resolve("src/conformance/flows"))) {
            return files.sorted().toList();
        }
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
        assertThat(IrCodec.hash(flow)).isEqualTo(IrCodec.hash(IrCodec.canonicalJson(flow)));
    }

    @Test
    void canonicalFormSortsKeysAndIsWhitespaceFree() {
        Flow flow = IrCodec.readFlow(login("[\"seq\", [\"screen\", \"identifier\", {\"b\": 1, \"a\": true}],"
                + " [\"call\", \"x.y\", {}], [\"succeed\"]]"));
        assertThat(IrCodec.canonicalJson(flow))
                .isEqualTo("[\"flow\",1,\"f\",1,[\"entry\",\"login\",\"authenticate\",[\"seq\","
                        + "[\"screen\",\"identifier\",{\"a\":true,\"b\":1}],[\"call\",\"x.y\"],[\"succeed\"]]]]");
    }

    @Test
    void hashCoversTheFormatAndIgnoresFormattingAndSourceRefs() {
        Flow compact = IrCodec.readFlow(login("[\"succeed\"]"));
        Flow spaced = IrCodec.readFlow(
                "[ \"flow\", 1, \"f\", 1, [ \"entry\", \"login\", \"authenticate\", [ \"succeed\" ] ] ]");
        Flow sourced = new Flow(
                "f",
                1,
                Map.of("login", new Entry(Entry.Kind.AUTHENTICATE, new Succeed(new SourceRef("f.yaml", 3, 1)))));
        assertThat(IrCodec.hash(compact))
                .startsWith("sha256:")
                .hasSize(7 + 64)
                .isEqualTo(IrCodec.hash(spaced))
                .isEqualTo(IrCodec.hash(sourced));
        assertThat(IrCodec.canonicalJson(compact)).startsWith("[\"flow\",1,");
        assertThat(IrCodec.hash(
                        IrCodec.readFlow("[\"flow\",1,\"f\",2,[\"entry\",\"login\",\"authenticate\",[\"succeed\"]]]")))
                .isNotEqualTo(IrCodec.hash(compact));
    }

    @Test
    void aNumberJacksonCannotHoldIsRefusedNotThrown() {
        assertThatThrownBy(() -> IrCodec.readFlow("[\"flow\",8e11111111111111111]"))
                .isInstanceOf(IrException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void aNumberOfAbsurdMagnitudeIsRefusedBeforeValidation() {
        assertThatThrownBy(() -> IrCodec.readFlow("[\"fhow\",0,\"f\",1,[\"x\",21474,8e111111836]]"))
                .isInstanceOf(IrException.class)
                .hasMessageContaining("digits or exponent");
    }

    @Test
    void refusesFormatsItDoesNotRead() {
        for (String format : List.of("0", "3", "99999999999")) {
            assertThatThrownBy(() -> IrCodec.readFlow(
                            "[\"flow\"," + format + ",\"f\",1,[\"entry\",\"login\",\"authenticate\",[\"succeed\"]]]"))
                    .isInstanceOfSatisfying(IrException.class, e -> {
                        assertThat(e.at()).isEqualTo("/1");
                        assertThat(e.reason()).startsWith("unsupported IR format " + format);
                    });
        }
        // The unversioned shape of before format 1 is not a flow at all
        assertThatThrownBy(() -> IrCodec.readFlow("[\"flow\",\"f\",1,[\"login\",[\"succeed\"]]]"))
                .isInstanceOf(IrException.class);
    }

    /** ADR 0032: format 2 is format 1 plus the approve kind, and only a flow with one is written in it. */
    @Test
    void anApproveEntryIsFormat2AndOnlyIt() {
        String approve = "[\"entry\",\"approve\",\"approve\",[\"succeed\"]]";
        String login = "[\"entry\",\"login\",\"authenticate\",[\"succeed\"]]";
        assertThatThrownBy(() -> IrCodec.readFlow("[\"flow\",1,\"f\",1," + login + "," + approve + "]"))
                .isInstanceOfSatisfying(IrException.class, e -> {
                    assertThat(e.at()).isEqualTo("/5/2");
                    assertThat(e.reason()).contains("IR format 2");
                });
        var two = IrCodec.readFlow("[\"flow\",2,\"f\",1," + login + "," + approve + "]");
        assertThat(IrCodec.toJson(two).get(1).asInt()).isEqualTo(2);
        var one = IrCodec.readFlow("[\"flow\",1,\"f\",1," + login + "]");
        assertThat(IrCodec.toJson(one).get(1).asInt()).isEqualTo(1);
        assertThat(IrCodec.errors(IrCodec.toJson(one))).isEmpty();
    }

    @Test
    void rejectsMalformedIrWithPointer() {
        List.of(
                        login("[\"loop\"]"),
                        login("[\"step\",\"Bad Id\",[\"succeed\"]]"),
                        login("[\"succeed\",1]"),
                        login("[\"seq\"]"),
                        "[\"flow\",1,\"f\",1,[\"entry\",\"login\",\"register\",[\"succeed\"]]]",
                        "[\"flow\",1,\"f\",1,[\"login\",[\"succeed\"]]]",
                        "[\"flow\",1,\"f\",1]",
                        login("[\"screen\",\"password\",{\"a\":1.5}]"),
                        "[\"flow\",1,\"f\",0,[\"entry\",\"login\",\"authenticate\",[\"succeed\"]]]",
                        login("[\"retry\",0,[\"succeed\"]]"),
                        login("[\"retry\",11,[\"succeed\"]]"),
                        login("[\"retry\",4294967297,[\"succeed\"]]"),
                        login("[\"choose\"]"),
                        login("[\"choose\",[]]"),
                        login("[\"choose\",[[\"succeed\"]]]"),
                        login("[\"when\",\"user.x\"]"),
                        login("[\"when\",\"secrets.x\",[\"succeed\"]]"),
                        login("[\"when\",\"hydra.acr_values\",[\"succeed\"]]"),
                        login("[\"when\",{\"xor\":[]},[\"succeed\"]]"),
                        login("[\"when\",{\"gt\":[\"user.n\",\"a\"]},[\"succeed\"]]"),
                        login("[\"when\",{\"and\":[]},[\"succeed\"]]"),
                        login("[\"set\",\"vars.x\",1]"),
                        login("[\"hook\",\"risk\"]"),
                        login("[\"hook\",\"risk\",\"md5:abc\"]"),
                        // A choose's pick and an option's guard live in their options objects
                        login("[\"choose\",[\"hook\",\"r\",\"" + HASH + "\"],[\"opt\",\"a\",[\"succeed\"]]]"),
                        login("[\"choose\",[[\"opt\",\"a\",[\"succeed\"]]],{}]"),
                        login("[\"choose\",[[\"opt\",\"a\",[\"succeed\"]]],{\"pick\":[\"succeed\"]}]"),
                        login("[\"choose\",[[\"opt\",\"a\",[\"succeed\"],{\"if\":\"user.a\"}]]]"),
                        login("[\"choose\",[[\"opt\",\"a\",[\"succeed\"],{\"guard\":\"secrets.a\"}]]]"))
                .forEach(bad -> {
                    assertThatThrownBy(() -> IrCodec.readFlow(bad)).as(bad).isInstanceOf(IrException.class);
                    try {
                        assertThat(SCHEMA.validate(MAPPER.readTree(bad)))
                                .as(bad)
                                .isNotEmpty();
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                });
        assertThatThrownBy(() -> IrCodec.readFlow(login("[\"seq\",[\"succeed\"],[\"nope\"]]")))
                .isInstanceOfSatisfying(
                        IrException.class, e -> assertThat(e.at()).isEqualTo("/4/3/2/0"));
    }

    @Test
    void duplicateEntryAndOptionIdsAreRejected() {
        assertThatThrownBy(
                        () -> IrCodec.readFlow("[\"flow\",1,\"f\",1,[\"entry\",\"a\",\"authenticate\",[\"succeed\"]],"
                                + "[\"entry\",\"a\",\"consent\",[\"succeed\"]]]"))
                .isInstanceOfSatisfying(
                        IrException.class, e -> assertThat(e.at()).isEqualTo("/5/1"));
        assertThatThrownBy(() -> IrCodec.readFlow(
                        login("[\"choose\",[[\"opt\",\"a\",[\"succeed\"]],[\"opt\",\"a\",[\"succeed\"]]]]")))
                .isInstanceOfSatisfying(
                        IrException.class, e -> assertThat(e.at()).isEqualTo("/4/3/1/1/1"));
    }

    @Test
    void guardsPicksAndPredicatesRoundTrip() {
        String json = login("[\"seq\",[\"when\",{\"and\":[\"user.a\",{\"not\":{\"exists\":\"vars.b\"}},"
                + "{\"or\":[{\"eq\":[\"user.c\",\"x\"]},{\"ne\":[\"user.c\",true]},{\"gt\":[\"user.n\",1]},"
                + "{\"lt\":[\"user.n\",9]},{\"has\":[\"host.acr_values\",\"faye:2fa\"]}]}]},"
                + "[\"set\",\"risk\",\"high\"]],"
                + "[\"choose\",[[\"opt\",\"a\",[\"succeed\"],{\"guard\":\"user.a\"}],"
                + "[\"opt\",\"b\",[\"fail\",\"no\"]]],"
                + "{\"pick\":[\"hook\",\"r\",\"" + HASH + "\"]}]]");
        Flow flow = IrCodec.readFlow(json);
        assertThat(IrCodec.canonicalJson(flow)).isEqualTo(json);
        var choose =
                (Choose) ((Seq) java.util.Objects.requireNonNull(flow.entries().get("login"))
                                .body())
                        .children()
                        .get(1);
        assertThat(choose.options().get(0).guard()).isEqualTo(new Predicate.Path("user.a"));
        assertThat(choose.options().get(1).guard()).isNull();
        assertThat(choose.pick()).isNotNull();
    }

    @Test
    void sourcesAreLookedUpByPointer() {
        Flow flow = IrCodec.readFlow(
                IrCodecTest.json(login("[\"seq\",[\"step\",\"a\",[\"succeed\"]],[\"succeed\"]]")),
                at -> new SourceRef("f.yaml", at.length(), 1));
        var seq = (Seq)
                java.util.Objects.requireNonNull(flow.entries().get("login")).body();
        assertThat(seq.source().line()).isEqualTo("/4/3".length());
        assertThat(((Step) seq.children().get(0)).body().source().line()).isEqualTo("/4/3/1/2".length());
    }

    @Test
    void displaysTheJsonAsAnSExpression() {
        Flow flow = IrCodec.readFlow(login("[\"seq\",[\"when\",{\"eq\":[\"vars.risk\",\"High risk\"]},[\"succeed\"]],"
                + "[\"choose\",[[\"opt\",\"a\",[\"fail\",\"no\"],{\"guard\":\"user.a\"}]]]]"));
        assertThat(IrCodec.display(flow)).isEqualTo("""
                        (flow 1 f 1
                          (entry login authenticate
                            (seq
                              (when {:eq (vars.risk "High risk")} (succeed))
                              (choose ((opt a (fail no) {:guard user.a}))))))""");
    }

    static com.fasterxml.jackson.databind.JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
