package faye.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import faye.dsl.FlowCompiler;
import faye.engine.InMemoryFlowVersions;
import faye.engine.InMemoryHookStore;
import faye.engine.TenantPolicy;
import faye.ir.HookDef;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The checker's view of hooks: pinned, granted, and never a way around a credential. */
class HookCheckTest {

    static final TenantPolicy GRANTED = new TenantPolicy(false, null, null, Set.of("ip-reputation"));

    static final String RISK = """
            flow: risk
            version: 1
            hooks:
              risk:
                source: |
                  export default async (ctx, cap) => ({ set: { risk: 'high' } })
                capabilities: { http: [ip-reputation] }
            login:
              steps:
                identify: { block: identifier, after: risk }
                credential: { block: password }
                second-factor:
                  if: { eq: [vars.risk, high] }
                  block: totp
            """;

    static FlowCompiler.Compiled compile(String yaml) {
        return FlowCompiler.compileWithHooks("tenant.yaml", yaml, name -> Optional.empty(), Fixtures.DSL);
    }

    static Report check(FlowCompiler.Compiled compiled, TenantPolicy policy) {
        return Checker.check(
                compiled.flow(),
                Fixtures.handlers(),
                Fixtures.screens(),
                policy,
                hash -> Optional.ofNullable(compiled.hooks().get(hash)));
    }

    static List<String> errors(Report report) {
        return report.errors().stream().map(Finding::toString).toList();
    }

    @Test
    void riskFlowPassesWhenItsEgressIsGranted() {
        Report report = check(compile(RISK), GRANTED);
        assertThat(report.errors()).isEmpty();
        // The hook may set vars.risk either way, so both the one and two factor paths exist
        assertThat(report.summary()).endsWith("assurance pwd (faye:1fa) to otp+pwd (faye:2fa)");
        assertThat(report.paths()).anyMatch(p -> p.describe().equals("identify > fail hook_error"));
    }

    @Test
    void egressTheTenantDidNotGrantIsAnError() {
        assertThat(errors(check(compile(RISK), TenantPolicy.DEFAULT)))
                .containsExactly("tenant.yaml:10:43: hook 'risk' calls egress target 'ip-reputation',"
                        + " which is not granted to this tenant");
    }

    @Test
    void hookThatIsNotStoredIsAnError() {
        var compiled = compile(RISK);
        Report report = Checker.check(
                compiled.flow(), Fixtures.handlers(), Fixtures.screens(), GRANTED, hash -> Optional.empty());
        assertThat(errors(report))
                .singleElement()
                .asString()
                .contains("hook 'risk' (sha256:")
                .endsWith("is not stored");
    }

    /** A hook can only set vars, and the checker never trusts what it set: both values are paths. */
    @Test
    void hookCannotStandInForACredential() {
        var compiled = compile("""
                flow: lax
                version: 1
                hooks:
                  trust: { source: "export default () => ({ set: { trusted: true } })" }
                login:
                  steps:
                    identify: { block: identifier, after: trust }
                    credential:
                      if: { not: vars.trusted }
                      block: password
                """);
        assertThat(errors(check(compiled, TenantPolicy.DEFAULT)))
                .containsExactly("tenant.yaml:9:11: a path succeeds without verifying a credential:"
                        + " identify > credential > (condition at 9 false) > succeed with no credential");
    }

    @Test
    void pickingHookAddsAPathWhereItFailsAndNoOthers() {
        var compiled = compile("""
                flow: pick
                version: 1
                hooks:
                  route: { source: "export default (ctx) => ({ pick: ctx.options[0] })" }
                login:
                  steps:
                    identify: { block: identifier }
                    credential:
                      pick: route
                      alternative:
                        password: { block: password }
                        email: { if: user.email_verified, block: email-code }
                """);
        Report report = check(compiled, TenantPolicy.DEFAULT);
        assertThat(report.errors()).isEmpty();
        assertThat(report.paths().stream().map(FlowPath::describe))
                .contains(
                        "identify > credential/password > succeed pwd",
                        "identify > credential/email > succeed email",
                        "identify > credential > fail hook_error")
                .noneMatch(p -> p.contains("no_method"));
    }

    @Test
    void publishStoresTheHooksBeforeTheVersion() {
        var compiled = compile(RISK);
        var versions = new InMemoryFlowVersions();
        var hooks = new InMemoryHookStore();
        var publisher =
                new Publisher(versions, hooks, Fixtures.handlers(), Fixtures.screens(), Map.of("risky", GRANTED));
        String hash = compiled.hooks().keySet().iterator().next();

        assertThatThrownBy(() -> publisher.publish("strict", compiled.flow(), compiled.hooks()))
                .isInstanceOf(CheckFailed.class);
        assertThat(hooks.get(hash)).isEmpty();
        assertThat(versions.current("strict")).isEmpty();

        publisher.publish("risky", compiled.flow(), compiled.hooks());
        assertThat(hooks.get(hash)).contains(compiled.hooks().get(hash));
        // A version referring to a stored hook checks without the definition at hand
        assertThat(publisher.check("risky", compiled.flow()).ok()).isTrue();
    }

    @Test
    void hookDefinitionIsContentAddressed() {
        var a = new HookDef("export default () => ({})", List.of("b", "a"), 200);
        var b = new HookDef("export default () => ({})", List.of("a", "b"), 200);
        assertThat(a.hash()).isEqualTo(b.hash()).matches("sha256:[0-9a-f]{64}");
        assertThat(HookDef.readJson(a.canonicalJson())).isEqualTo(a);
        assertThat(new HookDef("export default () => ({})", List.of("a", "b"), 201).hash())
                .isNotEqualTo(a.hash());
    }
}
