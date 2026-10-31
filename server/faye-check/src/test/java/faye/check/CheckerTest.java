package faye.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import faye.engine.FlowVersion;
import faye.engine.InMemoryFlowVersions;
import faye.engine.TenantPolicy;
import faye.engine.Verifier;
import faye.ir.Flow;
import faye.ir.IrCodec;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CheckerTest {

    static final TenantPolicy MFA = new TenantPolicy(true, null, null);

    static Report check(Flow flow) {
        return check(flow, TenantPolicy.DEFAULT);
    }

    static Report check(Flow flow, TenantPolicy policy) {
        return Checker.check(flow, Fixtures.handlers(), Fixtures.screens(), policy);
    }

    static List<String> errors(Report report) {
        return report.errors().stream().map(Finding::toString).toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"password-only", "passkey-totp", "partner-consent"})
    void shippedFlowsPass(String name) {
        Report report = check(Fixtures.tenantFlow(name));
        assertThat(report.errors()).isEmpty();
    }

    @Test
    void summarizesPathsAndAssurance() {
        assertThat(check(Fixtures.tenantFlow("password-only")).summary())
                .isEqualTo("login: 3 success paths, 5 failure paths; assurance pwd (faye:1fa) to pwd (faye:1fa)");
        assertThat(check(Fixtures.tenantFlow("passkey-totp")).summary())
                .startsWith("login: ")
                .endsWith("assurance user (faye:1fa) to hwk+pwd+user (faye:2fa)");
    }

    /** M3 exit: a flow missing a credential on one branch is rejected with a pointer to the YAML line. */
    @Test
    void branchWithoutCredentialPointsAtItsLine() {
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: lax
                version: 1
                login:
                  steps:
                    identify:
                      screen: identifier
                      use: identity.resolve
                    credential:
                      alternative:
                        password:
                          screen: password
                          use: password.verify
                        trusted:
                          if: { eq: [request.ip, "10.0.0.1"] }
                          set: { trusted: true }
                """);
        Report report = check(flow);
        assertThat(errors(report))
                .containsExactly("tenant.yaml:14:15: a path succeeds without verifying a credential:"
                        + " identify > credential/trusted > succeed with no credential");
    }

    @Test
    void skippedCredentialPointsAtTheCondition() {
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: lax
                version: 1
                login:
                  steps:
                    identify:
                      screen: identifier
                      use: identity.resolve
                    credential:
                      if: { not: { eq: [client.client_id, internal] } }
                      screen: password
                      use: password.verify
                """);
        assertThat(errors(check(flow)))
                .singleElement()
                .asString()
                .startsWith("tenant.yaml:9:11: a path succeeds without verifying a credential: identify > credential");
    }

    @Test
    void credentialBeforeIdentityIsAnError() {
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: backwards
                version: 1
                login:
                  steps:
                    credential:
                      screen: password
                      use: password.verify
                    identify:
                      screen: identifier
                      use: identity.resolve
                """);
        assertThat(errors(check(flow)))
                .singleElement()
                .asString()
                .startsWith("tenant.yaml:7:12: password.verify runs before any handler identifies the person");
    }

    @Test
    void mfaPolicyNeedsTwoFactorsOnEveryPath() {
        assertThat(errors(check(Fixtures.tenantFlow("password-only"), MFA)))
                .singleElement()
                .asString()
                .contains("the tenant requires MFA, but a path succeeds with faye:1fa");
        // The second factor depends on the account, which the checker cannot know
        assertThat(check(Fixtures.tenantFlow("passkey-totp"), MFA).errors()).isNotEmpty();
        // tenant.mfa_required is known when the flow is checked
        assertThat(check(Fixtures.conformanceFlow("mfa-policy"), MFA).errors()).isEmpty();
        assertThat(check(Fixtures.conformanceFlow("mfa-policy")).errors()).isEmpty();
    }

    @Test
    void passkeyWithoutUserVerificationIsOneFactor() {
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: passkey
                version: 1
                login:
                  steps:
                    identify:
                      screen: { type: identifier, passkey: true }
                      use: identity.resolve
                    credential:
                      screen: passkey
                      use: webauthn.assert
                """);
        Report report = check(flow, MFA);
        assertThat(errors(report)).singleElement().asString().contains("succeeds with faye:1fa");
        assertThat(report.paths().stream().filter(FlowPath::succeeded).map(FlowPath::amr))
                .containsExactly(List.of("hwk", "user"), List.of("user"), List.of("hwk"));
    }

    @Test
    void tenantAllowlists() {
        Flow flow = Fixtures.tenantFlow("passkey-totp");
        Report report = check(
                flow,
                new TenantPolicy(
                        false,
                        Set.of("identity.resolve", "password.verify", "webauthn.assert", "totp.verify"),
                        Set.of("identifier", "password", "passkey", "otp")));
        assertThat(errors(report))
                .containsExactly(
                        "passkey-totp.yaml:48:19: screen 'otp_enroll' is not enabled for this tenant",
                        "passkey-totp.yaml:49:16: handler 'totp.enroll' is not enabled for this tenant",
                        "passkey-totp.yaml:53:12: handler 'webauthn.register' is not enabled for this tenant");
    }

    @Test
    void schemaAndStepIds() {
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: typos
                version: 1
                login:
                  steps:
                    identify:
                      screen: identifyer
                      use: identity.resolve
                    credential:
                      screen: password
                      use: password.verfy
                      steps:
                        identify:
                          screen: password
                          use: password.verify
                """);
        assertThat(errors(check(flow)))
                .contains(
                        "tenant.yaml:6:15: unknown screen type 'identifyer'",
                        "tenant.yaml:10:12: unknown handler 'password.verfy'",
                        "tenant.yaml:12:9: step id 'identify' is already used at tenant.yaml:5:5");
    }

    @Test
    void warnings() {
        Report forms = check(Fixtures.conformanceFlow("forms"));
        assertThat(forms.warnings().stream().map(Finding::message))
                .contains(
                        "option 'low-risk' is never offered: its condition is false on every path",
                        "no path reaches faye:2fa; clients that request it in acr_values are refused");
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: odd
                version: 1
                login:
                  steps:
                    identify:
                      screen: identifier
                      use: identity.resolve
                    credential:
                      alternative:
                        password:
                          screen: password
                          use: password.verify
                    again:
                      retry: 2
                      use: password.verify
                    later:
                      if: { has: [auth.amr, otp] }
                      use: totp.verify
                """);
        assertThat(check(flow).warnings().stream().map(Finding::toString))
                .contains(
                        "tenant.yaml:10:9: a choose has a single option; use its body directly",
                        "tenant.yaml:14:14: retry without a screen asks nothing new on each attempt",
                        "tenant.yaml:17:11: never runs: its condition is false on every path that reaches it");
    }

    @Test
    void retryEnumeratesFailuresBeforeASuccess() {
        // password-only: succeed on attempt 1, 2 or 3, or fail all three; or, on any attempt, prove
        // someone other than a subject already bound, which ends the entry unretried
        List<FlowPath> credential = check(Fixtures.tenantFlow("password-only")).paths().stream()
                .filter(p -> p.events().stream().anyMatch(e -> e instanceof FlowPath.Called c && c.ok()))
                .toList();
        assertThat(credential.stream()
                        .map(p -> p.events().stream()
                                .filter(e -> e instanceof FlowPath.Called c
                                        && c.handler().equals("password.verify"))
                                .count()))
                .containsExactlyInAnyOrder(1L, 2L, 3L, 3L, 1L, 2L, 3L);
    }

    /** A later attempt runs with what an earlier one verified, so a condition false at first may hold. */
    @Test
    void retryCarriesStateIntoTheNextAttempt() {
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: shortcut
                version: 1
                login:
                  steps:
                    identify:
                      screen: identifier
                      use: identity.resolve
                    credentials:
                      retry: 3
                      steps:
                        done:
                          if: { has: [auth.amr, pwd] }
                          fail: unreachable
                        password:
                          screen: password
                          use: password.verify
                        totp:
                          screen: otp
                          use: totp.verify
                """);
        // Attempt 1: password verifies, TOTP fails; attempt 2 sees pwd and ends the section
        assertThat(check(flow).warnings().stream().map(Finding::message))
                .doesNotContain("never runs: its condition is false on every path that reaches it");
        assertThat(check(flow).paths().stream()
                        .filter(p -> p.end() instanceof FlowPath.Failed f
                                && f.reason().equals("unreachable")))
                .isNotEmpty();
    }

    @Test
    void retriedCredentialBeforeIdentityIsAnError() {
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: sneaky
                version: 1
                login:
                  steps:
                    first:
                      retry: 2
                      steps:
                        early:
                          if: vars.again
                          screen: password
                          use: password.verify
                        mark:
                          set: { again: true }
                          screen: identifier
                          use: webauthn.register
                    identify:
                      screen: identifier
                      use: identity.resolve
                    credential:
                      screen: password
                      use: password.verify
                """);
        assertThat(errors(check(flow)))
                .anySatisfy(
                        e -> assertThat(e).contains("password.verify runs before any handler identifies the person"));
    }

    @Test
    void publisherChecksBeforePublishingAndOnRollback() {
        var versions = new InMemoryFlowVersions();
        Flow passwordOnly = Fixtures.tenantFlow("password-only");
        Flow mfa = Fixtures.conformanceFlow("mfa-policy");
        var open = new Publisher(versions, Fixtures.handlers(), Fixtures.screens(), Map.of());
        FlowVersion first = open.publish("t", passwordOnly);
        FlowVersion second = open.publish("t", mfa);
        assertThat(versions.history("t")).containsExactly(first.hash(), second.hash());

        assertThatThrownBy(() -> open.publish("t", Fixtures.conformanceFlow("no-credential")))
                .isInstanceOf(CheckFailed.class)
                .hasMessageContaining("without verifying a credential");
        assertThat(versions.current("t")).contains(second);

        // Under a policy that now requires MFA, the password only version cannot come back
        var strict = new Publisher(versions, Fixtures.handlers(), Fixtures.screens(), Map.of("t", MFA));
        assertThatThrownBy(() -> strict.rollback("t", first.hash())).isInstanceOf(CheckFailed.class);
        assertThat(open.rollback("t", first.hash())).isEqualTo(first);
        assertThat(versions.current("t")).contains(first);
        assertThatThrownBy(() -> open.rollback("other", first.hash())).isInstanceOf(NoSuchElementException.class);
    }

    // ---- ADR 0024: rules keyed by entry kind, and the verifier's catalog checks ----

    @Test
    void aConsentEntryCannotVerifyACredential() {
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { screen: identifier, use: identity.resolve }
                    credential: { screen: password, use: password.verify }
                consent:
                  steps:
                    again: { screen: password, use: password.verify }
                """);
        assertThat(errors(check(flow)))
                .containsExactly("tenant.yaml:9:37: password.verify verifies a credential; a consent entry only grants"
                        + " access to a subject its authenticate entry proved");
    }

    @Test
    void aCredentialWriteNeedsAVerifiedSubjectFirst() {
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { screen: identifier, use: identity.resolve }
                    enroll: { screen: { type: passkey, register: true }, use: webauthn.register }
                    credential: { screen: password, use: password.verify }
                """);
        assertThat(errors(check(flow)))
                .isNotEmpty()
                .allSatisfy(e -> assertThat(e)
                        .startsWith("tenant.yaml:6:63: webauthn.register needs a subject a credential verified"
                                + " earlier in the flow, but runs first on the path"));
    }

    @Test
    void entriesAreOnePerKindWithAnAuthenticateEntry() {
        String succeed = "[\"seq\",[\"step\",\"c\",[\"seq\",[\"screen\",\"identifier\"],"
                + "[\"call\",\"identity.resolve\"],[\"screen\",\"password\"],[\"call\",\"password.verify\"]]],"
                + "[\"succeed\"]]";
        Flow consentOnly = IrCodec.readFlow("[\"flow\",1,\"f\",1,[\"entry\",\"grant\",\"consent\",[\"succeed\"]]]");
        assertThat(errors(check(consentOnly))).containsExactly(": a flow needs an authenticate entry");
        Flow twice = IrCodec.readFlow("[\"flow\",1,\"f\",1,[\"entry\",\"a\",\"authenticate\"," + succeed + "],"
                + "[\"entry\",\"b\",\"authenticate\"," + succeed + "]]");
        assertThat(errors(check(twice))).contains(": entry 'b' is a second authenticate entry; a flow has at most one");
    }

    @Test
    void theVerifierRefusesUnknownScreenOptionsAndBadArgs() {
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { screen: { type: identifier, pasky: true }, use: identity.resolve }
                    credential: { screen: password, use: password.verify }
                """);
        assertThat(errors(check(flow)))
                .containsExactly("tenant.yaml:5:25: screen 'identifier' has no option 'pasky'; it takes [passkey]");
        Flow args = IrCodec.readFlow("[\"flow\",1,\"f\",1,[\"entry\",\"login\",\"authenticate\",[\"seq\","
                + "[\"call\",\"identity.resolve\",{\"lowercase\":true}],"
                + "[\"call\",\"password.verify\"],[\"succeed\"]]]]");
        assertThat(errors(check(args)))
                .singleElement()
                .satisfies(e -> assertThat(e).startsWith(": args of 'identity.resolve': "));
    }

    @Test
    void aHostPathOutsideTheContractIsRefusedAtPublish() {
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    identify:
                      if: { or: [host.prompt, { has: [host.acr_values, "faye:2fa"] }, host.context.origin] }
                      screen: identifier
                      use: identity.resolve
                    credential: { screen: password, use: password.verify }
                """);
        assertThat(errors(check(flow)).stream().filter(e -> e.contains("host contract")))
                .singleElement()
                .satisfies(e ->
                        assertThat(e).startsWith("tenant.yaml:6:11: 'host.prompt' is not part of the host contract"));
    }

    @Test
    void aStoredVersionThatReadsAnUnknownHostPathStillLoads() {
        Flow stored = IrCodec.readFlow("[\"flow\",1,\"f\",1,[\"entry\",\"login\",\"authenticate\",[\"seq\","
                + "[\"when\",\"host.prompt\",[\"set\",\"x\",true]],"
                + "[\"screen\",\"identifier\"],[\"call\",\"identity.resolve\"],"
                + "[\"screen\",\"password\"],[\"call\",\"password.verify\"],[\"succeed\"]]]]");
        // L0 and L1 hold it; only a new publish (L2) is held to the closed list
        assertThat(Verifier.verify(stored, Fixtures.handlers(), Fixtures.screens()))
                .isEmpty();
        assertThat(errors(check(stored)))
                .singleElement()
                .satisfies(e -> assertThat(e).contains("'host.prompt'"));
    }
}
