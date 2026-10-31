package faye.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import faye.ir.Call;
import faye.ir.IrCodec;
import faye.ir.Screen;
import faye.ir.Seq;
import faye.ir.SourceRef;
import faye.ir.Step;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FlowCompilerTest {

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
        var expected = IrCodec.readFlow(
                Files.readString(Path.of(System.getProperty("faye.repoRoot"), "conformance/flows/default-login.json")));
        assertThat(IrCodec.canonicalJson(FlowCompiler.compile("default-login.yaml", DEFAULT_LOGIN)))
                .isEqualTo(IrCodec.canonicalJson(expected));
    }

    @Test
    void recordsSourcePositions() {
        var flow = FlowCompiler.compile("default-login.yaml", DEFAULT_LOGIN);
        var login = (Seq) java.util.Objects.requireNonNull(flow.sections().get("login"));
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
                .isEqualTo("[\"flow\",\"f\",2,[\"login\",[\"seq\","
                        + "[\"step\",\"resolve\",[\"call\",\"identity.resolve\"]],[\"succeed\"]]]]");
    }

    @Test
    void reportsEveryErrorWithItsLine() {
        assertThatThrownBy(() -> FlowCompiler.compile("bad.yaml", """
                flow: Bad
                version: one
                login:
                  steps:
                    identify:
                      screen: identifier
                      repeat: 3
                    empty: {}
                    identify: { use: identity.resolve }
                """))
                .isInstanceOfSatisfying(
                        DslException.class,
                        e -> assertThat(e.diagnostics())
                                .extracting(Diagnostic::toString)
                                .containsExactly(
                                        "bad.yaml:1:7: flow id must match [a-z][a-z0-9_-]*",
                                        "bad.yaml:2:10: version must be a positive integer",
                                        "bad.yaml:9:5: duplicate key 'identify'",
                                        "bad.yaml:7:7: unknown step key 'repeat'",
                                        "bad.yaml:8:5: step needs one of 'set', 'hook', 'screen', 'use', "
                                                + "'alternative', 'steps' or 'fail'"));
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
    void requiresLoginSection() {
        assertThatThrownBy(() -> FlowCompiler.compile("f.yaml", "flow: f\nversion: 1\n"))
                .hasMessageContaining("missing 'login' section");
    }

    @Test
    void compilesEveryStepKey() {
        var flow = FlowCompiler.compile("f.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    identify:
                      screen: { type: identifier, passkey: true }
                      use: identity.resolve
                    credential:
                      if: { not: { has: [auth.amr, hwk] } }
                      set: { risk: high, tries: 2, strict: false }
                      alternative:
                        password:
                          retry: 3
                          screen: password
                          use: password.verify
                        blocked:
                          if: { eq: [vars.risk, high] }
                          steps:
                            warn: { screen: { type: error, code: blocked } }
                            stop: { fail: blocked }
                """);
        assertThat(IrCodec.canonicalJson(flow))
                .isEqualTo("[\"flow\",\"f\",1,[\"login\",[\"seq\","
                        + "[\"step\",\"identify\",[\"seq\",[\"screen\",\"identifier\",{\"passkey\":true}],"
                        + "[\"call\",\"identity.resolve\"]]],"
                        + "[\"step\",\"credential\",[\"when\",{\"not\":{\"has\":[\"auth.amr\",\"hwk\"]}},[\"seq\","
                        + "[\"set\",\"risk\",\"high\"],[\"set\",\"tries\",2],[\"set\",\"strict\",false],"
                        + "[\"choose\",[\"opt\",\"password\",[\"retry\",3,[\"seq\",[\"screen\",\"password\"],"
                        + "[\"call\",\"password.verify\"]]]],"
                        + "[\"opt\",\"blocked\",[\"when\",{\"eq\":[\"vars.risk\",\"high\"]},[\"seq\","
                        + "[\"step\",\"warn\",[\"screen\",\"error\",{\"code\":\"blocked\"}]],"
                        + "[\"step\",\"stop\",[\"fail\",\"blocked\"]]]]]]]]],"
                        + "[\"succeed\"]]]]");
    }

    @Test
    void reportsBadPredicatesAndRetriesAtTheirLine() {
        assertThatThrownBy(() -> FlowCompiler.compile("bad.yaml", """
                        flow: f
                        version: 1
                        login:
                          steps:
                            a:
                              if: { xor: [user.a] }
                              retry: 0
                              use: password.verify
                            b:
                              alternative: {}
                        """))
                .isInstanceOfSatisfying(
                        DslException.class,
                        e -> assertThat(e.diagnostics())
                                .extracting(Diagnostic::toString)
                                .containsExactly(
                                        "bad.yaml:6:11: invalid predicate: /xor: unknown operator xor",
                                        "bad.yaml:7:14: retry must be an integer from 1 to 10",
                                        "bad.yaml:10:20: 'alternative' must be a non empty mapping",
                                        "bad.yaml:9:5: step needs one of 'set', 'hook', 'screen', 'use', "
                                                + "'alternative', 'steps' or 'fail'"));
    }
}
