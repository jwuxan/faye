package faye.ir;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The three places that name the IR's forms agree (ADR 0024): the schema's form names, the Java
 * node types the codec reads them into, and the sections of the normative spec. A form added to
 * one and not the others fails here.
 */
class IrAgreementTest {

    static final Path ROOT = Path.of(System.getProperty("faye.repoRoot"));
    static final String HASH = "sha256:" + "0".repeat(64);

    /** A flow that uses every form. */
    static final String EVERY_FORM = "[\"flow\",1,\"f\",1,[\"entry\",\"login\",\"authenticate\",[\"seq\","
            + "[\"step\",\"s\",[\"screen\",\"password\"]],[\"call\",\"x.y\"],[\"hook\",\"h\",\"" + HASH + "\"],"
            + "[\"choose\",[[\"opt\",\"a\",[\"set\",\"k\",1]]]],[\"when\",\"user.a\",[\"retry\",2,[\"fail\",\"no\"]]],"
            + "[\"succeed\"]]]]";

    @Test
    void schemaJavaAndSpecNameTheSameForms() throws IOException {
        JsonNode schema = new ObjectMapper()
                .readTree(ROOT.resolve("schema/ir.schema.json").toFile());
        Set<String> forms = new TreeSet<>();
        schema.at("/$defs/node/prefixItems/0/enum").forEach(f -> forms.add(f.asText()));

        // Every form reads into its own node type, and those are all of Node's types
        Flow flow = IrCodec.readFlow(EVERY_FORM);
        Set<Class<?>> types = new HashSet<>();
        List<String> heads = new ArrayList<>();
        collect(IrCodec.toJson(flow).get(4).get(3), heads);
        walk(java.util.Objects.requireNonNull(flow.entries().get("login")).body(), types);
        assertThat(new TreeSet<>(heads)).isEqualTo(forms);
        assertThat(types).containsExactlyInAnyOrder(Node.class.getPermittedSubclasses());

        // The spec's Forms has a section per form and one for opt, the alternative of a choose
        Set<String> sections = new TreeSet<>();
        boolean inForms = false;
        for (String line : Files.readAllLines(ROOT.resolve("docs/roadmap/ir-spec.md"))) {
            if (line.startsWith("## ")) inForms = "## Forms".equals(line);
            if (inForms && line.startsWith("### ")) {
                sections.add(line.substring(4).trim());
            }
        }
        Set<String> expected = new TreeSet<>(forms);
        expected.add("opt");
        assertThat(sections).isEqualTo(expected);
    }

    private static void collect(JsonNode node, List<String> heads) {
        if (!node.isArray() || node.isEmpty()) return;
        if (node.get(0).isTextual() && !"opt".equals(node.get(0).asText())) {
            heads.add(node.get(0).asText());
        }
        node.forEach(child -> collect(child, heads));
    }

    private static void walk(Node node, Set<Class<?>> types) {
        types.add(node.getClass());
        switch (node) {
            case Seq s -> s.children().forEach(c -> walk(c, types));
            case Step s -> walk(s.body(), types);
            case When w -> walk(w.body(), types);
            case Retry r -> walk(r.body(), types);
            case Choose c -> c.options().forEach(o -> walk(o.body(), types));
            case Screen s -> {}
            case Call c -> {}
            case Hook h -> {}
            case SetVar s -> {}
            case Succeed s -> {}
            case Fail f -> {}
        }
    }
}
