package faye.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import faye.ir.HookDef;
import faye.ir.IrCodec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HookCompileTest {

    static final String RISK_JS = "export default async (ctx, cap) => ({ set: { risk: 'low' } })\n";

    static FlowCompiler.Compiled compile(String yaml) {
        Map<String, String> files = Map.of("risk.js", RISK_JS);
        return FlowCompiler.compileWithHooks(
                "t.yaml", yaml, name -> Optional.ofNullable(files.get(name)), FlowCompilerTest.DSL);
    }

    @Test
    void hookStepAndPickCompileToPinnedHooks() {
        var compiled = compile("""
                flow: risk
                version: 1
                hooks:
                  risk:
                    file: risk.js
                    capabilities: { http: [ip-reputation] }
                    timeout_ms: 300
                  route: { source: "export default () => ({})" }
                login:
                  steps:
                    identify: { block: identifier, after: risk }
                    credential:
                      pick: route
                      alternative:
                        password: { block: password, with: { attempts: 1 } }
                        otp: { block: totp, with: { attempts: 1 } }
                """);
        HookDef risk = new HookDef(RISK_JS, List.of("ip-reputation"), 300);
        HookDef route = new HookDef("export default () => ({})", List.of(), HookDef.DEFAULT_TIMEOUT_MS);
        assertThat(compiled.hooks()).isEqualTo(Map.of(risk.hash(), risk, route.hash(), route));
        assertThat(IrCodec.canonicalJson(compiled.flow()))
                .isEqualTo("[\"flow\",1,\"risk\",1,[\"entry\",\"login\",\"authenticate\",[\"seq\","
                        + "[\"step\",\"identify\",[\"seq\",[\"screen\",\"identifier\"],"
                        + "[\"call\",\"identity.resolve\"],[\"hook\",\"risk\",\"" + risk.hash() + "\"]]],"
                        + "[\"step\",\"credential\",[\"choose\",["
                        + "[\"opt\",\"password\",[\"seq\",[\"screen\",\"password\"],[\"call\",\"password.verify\"]]],"
                        + "[\"opt\",\"otp\",[\"seq\",[\"screen\",\"otp\"],[\"call\",\"totp.verify\"]]]],"
                        + "{\"pick\":[\"hook\",\"route\",\"" + route.hash() + "\"]}]],"
                        + "[\"succeed\"]]]]");
        // The IR reads back to the same flow
        assertThat(IrCodec.canonicalJson(IrCodec.readFlow(IrCodec.canonicalJson(compiled.flow()))))
                .isEqualTo(IrCodec.canonicalJson(compiled.flow()));
    }

    @Test
    void editingAHookChangesTheFlowHash() {
        String flow = """
                flow: risk
                version: 1
                hooks:
                  risk: { source: "%s" }
                login:
                  steps:
                    identify: { block: identifier, before: risk }
                """;
        var a = compile(flow.formatted("export default () => ({})"));
        var b = compile(flow.formatted("export default () => ({ set: { x: 1 } })"));
        assertThat(IrCodec.hash(a.flow())).isNotEqualTo(IrCodec.hash(b.flow()));
    }

    @Test
    void reportsHookErrorsAtTheirLine() {
        assertThatThrownBy(() -> compile("""
                        flow: bad
                        version: 1
                        hooks:
                          missing: { file: nope.js }
                          traversal: { file: ../secret.js }
                          caps: { source: "x", capabilities: { fs: [root] } }
                          slow: { source: "x", timeout_ms: 5000 }
                          empty: { timeout_ms: 100 }
                        login:
                          steps:
                            a: { block: identifier, before: undeclared }
                            b: { pick: missing, alternative: { x: { fail: nope }, y: { fail: nope } } }
                        """))
                .isInstanceOfSatisfying(
                        DslException.class,
                        e -> assertThat(e.diagnostics().stream().map(Diagnostic::toString))
                                .containsExactly(
                                        "t.yaml:4:20: hook file 'nope.js' not found",
                                        "t.yaml:5:22: hook file must match [a-z0-9][a-z0-9_-]*\\.js",
                                        "t.yaml:6:40: unknown capability 'fs'; hooks may have 'http'",
                                        "t.yaml:7:36: timeout_ms must be an integer from 1 to 2000",
                                        "t.yaml:8:10: hook needs 'file' or 'source'",
                                        "t.yaml:11:37: unknown hook 'undeclared'; declare it under 'hooks'"));
    }

    @Test
    void plainCompileHasNoFiles() {
        assertThatThrownBy(() -> FlowCompiler.compile("t.yaml", """
                        flow: f
                        version: 1
                        hooks:
                          risk: { file: risk.js }
                        login:
                          steps:
                            identify: { block: identifier, after: risk }
                        """))
                .isInstanceOf(DslException.class)
                .hasMessageContaining("hook file 'risk.js' not found");
    }
}
