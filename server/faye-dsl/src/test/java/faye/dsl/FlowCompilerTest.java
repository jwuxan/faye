package faye.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import faye.ir.Call;
import faye.ir.Choose;
import faye.ir.IrCodec;
import faye.ir.Predicate;
import faye.ir.Screen;
import faye.ir.Seq;
import faye.ir.SourceRef;
import faye.ir.Step;
import faye.ir.When;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class FlowCompilerTest {

    static final Path ROOT = Path.of(System.getProperty("faye.repoRoot"));
    static final Path TENANT_FLOWS = ROOT.resolve("server/faye-app/src/main/resources/flows");
    static final Path RESOURCES = ROOT.resolve("server/faye-dsl/src/test/resources");

    static final String DEFAULT_LOGIN = """
            flow: default-login
            version: 1

            login:
              steps:
                identify:
                  screen: identifier
                  use: identity.resolve
                credential:
                  screen: password
                  use: password.verify
            """;

    @Test
    void compilesToTheConformanceIr() throws Exception {
        var expected = IrCodec.readFlow(Files.readString(ROOT.resolve("conformance/flows/default-login.json")));
        assertThat(IrCodec.canonicalJson(FlowCompiler.compile("default-login.yaml", DEFAULT_LOGIN)))
                .isEqualTo(IrCodec.canonicalJson(expected));
    }

    @Test
    void recordsSourcePositions() {
        var flow = FlowCompiler.compile("default-login.yaml", DEFAULT_LOGIN);
        var login = (Seq) Objects.requireNonNull(flow.entries().get("login")).body();
        var credential = (Step) login.children().get(1);
        var body = (Seq) credential.body();

        assertThat(login.source()).isEqualTo(new SourceRef("default-login.yaml", 4, 1));
        assertThat(credential.source()).isEqualTo(new SourceRef("default-login.yaml", 9, 5));
        assertThat(((Screen) body.children().get(0)).source().line()).isEqualTo(10);
        assertThat(((Call) body.children().get(1)).source().line()).isEqualTo(11);
    }

    @Test
    void singleKeyStepCompilesWithoutSeq() {
        var flow = FlowCompiler.compile("f.yaml", """
                flow: f
                version: 2
                login:
                  steps:
                    resolve: { use: identity.resolve }
                """);
        assertThat(IrCodec.canonicalJson(flow))
                .isEqualTo("[\"flow\",1,\"f\",2,[\"entry\",\"login\",\"authenticate\",[\"seq\","
                        + "[\"step\",\"resolve\",[\"call\",\"identity.resolve\"]],[\"succeed\"]]]]");
    }

    @Test
    void anOptionsIfIsItsGuardAndAStepsIfIsAWhen() {
        var flow = FlowCompiler.compile("f.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    mfa:
                      if: user.mfa_required
                      alternative:
                        totp: { if: user.has_totp, screen: otp, use: totp.verify }
                        password: { screen: password, use: password.verify }
                """);
        var step = (Step)
                ((Seq) Objects.requireNonNull(flow.entries().get("login")).body())
                        .children()
                        .get(0);
        var when = (When) step.body();
        var choose = (Choose) when.body();
        assertThat(choose.options().get(0).guard()).isEqualTo(new Predicate.Path("user.has_totp"));
        assertThat(choose.options().get(0).source().line()).isEqualTo(8);
        assertThat(choose.options().get(1).guard()).isNull();
    }

    @Test
    void hydraPathsLowerToHostPaths() {
        var flow = FlowCompiler.compile("f.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    a:
                      if: { or: [hydra.skip, { has: [hydra.acr_values, faye:2fa] }, { exists: hydra.login_hint },
                                 { eq: [user.note, hydra.literal] }] }
                      use: identity.resolve
                """);
        assertThat(IrCodec.canonicalJson(flow))
                .contains("{\"or\":[\"host.skip\",{\"has\":[\"host.acr_values\",\"faye:2fa\"]},"
                        + "{\"exists\":\"host.login_hint\"},{\"eq\":[\"user.note\",\"hydra.literal\"]}]}");
    }

    @Test
    void reportsYamlSyntaxErrors() {
        assertThatThrownBy(() -> FlowCompiler.compile("broken.yaml", "flow: [unclosed\n"))
                .isInstanceOfSatisfying(
                        DslException.class,
                        e -> assertThat(e.diagnostics().get(0).source().origin())
                                .isEqualTo("broken.yaml"));
    }

    @Test
    void refusesYamlPastItsLimitsAsADiagnostic() {
        String deep = "a: ".repeat(200) + "b\n";
        assertThatThrownBy(() -> FlowCompiler.compile("deep.yaml", deep)).isInstanceOf(DslException.class);
    }

    // ---- goldens: each shipped tenant flow lowers to the IR checked in beside this test ----

    static Stream<Path> tenantFlows() throws IOException {
        try (Stream<Path> files = Files.list(TENANT_FLOWS)) {
            return files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList().stream();
        }
    }

    /**
     * The golden is the flow's display notation, which shows one JSON document only, so equal text
     * is equal canonical IR. {@code FAYE_REGENERATE=1} rewrites it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("tenantFlows")
    void tenantFlowLowersToItsGolden(Path yaml) throws IOException {
        String name = yaml.getFileName().toString().replace(".yaml", "");
        var compiled = FlowCompiler.compileWithHooks(yaml.getFileName().toString(), Files.readString(yaml), file -> {
            try {
                return Optional.of(Files.readString(TENANT_FLOWS.resolve(file)));
            } catch (IOException e) {
                return Optional.empty();
            }
        });
        String actual = IrCodec.display(compiled.flow()) + "\n";
        Path golden = RESOURCES.resolve("golden/" + name + ".ir");
        if ("1".equals(System.getenv("FAYE_REGENERATE"))) {
            Files.writeString(golden, actual);
            return;
        }
        assertThat(golden)
                .as("golden missing; run FAYE_REGENERATE=1 ./gradlew :faye-dsl:test")
                .exists();
        assertThat(actual)
                .as("%s lowers to other IR than its golden; rerun with FAYE_REGENERATE=1 if intended", name)
                .isEqualTo(Files.readString(golden));
    }

    // ---- diagnostics: each file lists, in comments, exactly what compiling it reports ----

    static Stream<Path> diagnostics() throws IOException {
        try (Stream<Path> files = Files.list(RESOURCES.resolve("diagnostics"))) {
            return files.sorted().toList().stream();
        }
    }

    /** Lines {@code # expect: line:column: message}, in order, are every diagnostic of the file. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("diagnostics")
    void reportsWhatTheFileExpects(Path file) throws IOException {
        String yaml = Files.readString(file);
        List<String> expected = new ArrayList<>();
        yaml.lines()
                .filter(l -> l.startsWith("# expect: "))
                .forEach(l -> expected.add(l.substring("# expect: ".length())));
        String origin = file.getFileName().toString();
        assertThatThrownBy(() -> FlowCompiler.compile(origin, yaml))
                .isInstanceOfSatisfying(
                        DslException.class,
                        e -> assertThat(e.diagnostics())
                                .extracting(d -> d.toString().substring(origin.length() + 1))
                                .containsExactlyElementsOf(expected));
    }
}
