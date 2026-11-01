package faye.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import faye.engine.FlowVersion;
import faye.engine.InMemoryFlowVersions;
import faye.engine.TenantPolicy;
import faye.engine.Verifier;
import faye.ir.Flow;
import faye.ir.HookDef;
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
                      block: identifier
                    credential:
                      if: { not: { eq: [request.ip, "10.0.0.1"] } }
                      alternative:
                        password:
                          block: password
                        passkey:
                          block: passkey
                """);
        Report report = check(flow);
        assertThat(errors(report))
                .containsExactly("tenant.yaml:8:11: a path succeeds without verifying a credential:"
                        + " identify > credential > (condition at 8 false) > succeed with no credential");
    }

    @Test
    void skippedCredentialPointsAtTheCondition() {
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: lax
                version: 1
                login:
                  steps:
                    identify:
                      block: identifier
                    credential:
                      if: { not: { eq: [client.client_id, internal] } }
                      block: password
                """);
        assertThat(errors(check(flow)))
                .singleElement()
                .asString()
                .startsWith("tenant.yaml:8:11: a path succeeds without verifying a credential: identify > credential");
    }

    /** The YAML surface puts the Identifier first (ADR 0030); the checker holds IR to the same rule. */
    @Test
    void credentialBeforeIdentityIsAnError() {
        Flow flow = IrCodec.readFlow("[\"flow\",1,\"backwards\",1,[\"entry\",\"login\",\"authenticate\",[\"seq\","
                + "[\"step\",\"credential\",[\"seq\",[\"screen\",\"password\"],[\"call\",\"password.verify\"]]],"
                + "[\"step\",\"identify\",[\"seq\",[\"screen\",\"identifier\"],[\"call\",\"identity.resolve\"]]],"
                + "[\"succeed\"]]]]");
        assertThat(errors(check(flow)))
                .singleElement()
                .asString()
                .startsWith(": password.verify runs before any handler identifies the person");
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
                      with: { passkey: true }
                      block: identifier
                    credential:
                      block: passkey
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
                        "passkey-totp.yaml:42:18: screen 'otp_enroll' is not enabled for this tenant",
                        "passkey-totp.yaml:42:18: handler 'totp.enroll' is not enabled for this tenant",
                        "passkey-totp.yaml:45:14: handler 'webauthn.register' is not enabled for this tenant",
                        "passkey-totp.yaml:49:14: screen 'consent' is not enabled for this tenant",
                        "passkey-totp.yaml:49:14: handler 'consent.record' is not enabled for this tenant");
    }

    @Test
    void schemaAndStepIds() {
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: typos
                version: 1
                login:
                  steps:
                    identify:
                      block: identifier
                    group:
                      steps:
                        identify:
                          block: password
                """);
        assertThat(errors(check(flow)))
                .contains("tenant.yaml:9:9: step id 'identify' is already used at tenant.yaml:5:5");
        // Blocks fix their screen and handler (ADR 0030); IR may still name ones the catalogs lack
        String identify =
                "[\"step\",\"identify\",[\"seq\",[\"screen\",\"identifyer\"],[\"call\",\"identity.resolve\"]]],";
        String credential =
                "[\"step\",\"credential\",[\"seq\",[\"screen\",\"password\"],[\"call\",\"password.verfy\"]]],";
        String flow0 = "[\"flow\",1,\"typos\",1,[\"entry\",\"login\",\"authenticate\",[\"seq\",";
        assertThat(errors(check(IrCodec.readFlow(flow0 + identify + "[\"succeed\"]]]]"))))
                .contains(": unknown screen type 'identifyer'");
        assertThat(errors(check(IrCodec.readFlow(
                        flow0 + identify.replace("identifyer", "identifier") + credential + "[\"succeed\"]]]]"))))
                .contains(": unknown handler 'password.verfy'");
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
                      block: identifier
                    credential:
                      block: password
                    later:
                      if: { has: [auth.amr, otp] }
                      block: totp
                """);
        assertThat(check(flow).warnings().stream().map(Finding::toString))
                .contains("tenant.yaml:10:11: never runs: its condition is false on every path that reaches it");
        // The YAML surface refuses these shapes (ADR 0030); IR may still have them
        Flow ir = IrCodec.readFlow("[\"flow\",1,\"odd\",1,[\"entry\",\"login\",\"authenticate\",[\"seq\","
                + "[\"step\",\"identify\",[\"seq\",[\"screen\",\"identifier\"],[\"call\",\"identity.resolve\"]]],"
                + "[\"step\",\"credential\",[\"choose\",[[\"opt\",\"password\","
                + "[\"seq\",[\"screen\",\"password\"],[\"call\",\"password.verify\"]]]]]],"
                + "[\"step\",\"again\",[\"retry\",2,[\"call\",\"password.verify\"]]],"
                + "[\"succeed\"]]]]");
        assertThat(check(ir).warnings().stream().map(Finding::message))
                .contains(
                        "a choose has a single option; use its body directly",
                        "retry without a screen asks nothing new on each attempt");
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
        // A group does not retry in the YAML surface (ADR 0030); IR may
        Flow flow = IrCodec.readFlow("[\"flow\",1,\"shortcut\",1,[\"entry\",\"login\",\"authenticate\",[\"seq\","
                + "[\"step\",\"identify\",[\"seq\",[\"screen\",\"identifier\"],[\"call\",\"identity.resolve\"]]],"
                + "[\"step\",\"credentials\",[\"retry\",3,[\"seq\","
                + "[\"step\",\"done\",[\"when\",{\"has\":[\"auth.amr\",\"pwd\"]},[\"fail\",\"unreachable\"]]],"
                + "[\"step\",\"password\",[\"seq\",[\"screen\",\"password\"],[\"call\",\"password.verify\"]]],"
                + "[\"step\",\"totp\",[\"seq\",[\"screen\",\"otp\"],[\"call\",\"totp.verify\"]]]]]],"
                + "[\"succeed\"]]]]");
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
        Flow flow = IrCodec.readFlow("[\"flow\",1,\"sneaky\",1,[\"entry\",\"login\",\"authenticate\",[\"seq\","
                + "[\"step\",\"first\",[\"retry\",2,[\"seq\","
                + "[\"step\",\"early\",[\"when\",\"vars.again\","
                + "[\"seq\",[\"screen\",\"password\"],[\"call\",\"password.verify\"]]]],"
                + "[\"step\",\"mark\",[\"seq\",[\"set\",\"again\",true],[\"screen\",\"identifier\"],"
                + "[\"call\",\"webauthn.register\"]]]]]],"
                + "[\"step\",\"identify\",[\"seq\",[\"screen\",\"identifier\"],[\"call\",\"identity.resolve\"]]],"
                + "[\"step\",\"credential\",[\"seq\",[\"screen\",\"password\"],[\"call\",\"password.verify\"]]],"
                + "[\"succeed\"]]]]");
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
                    identify: { block: identifier }
                    credential: { block: password }
                    consent: { block: consent }
                    again: { block: password }
                """);
        assertThat(errors(check(flow)))
                .containsExactly("tenant.yaml:8:21: password.verify verifies a credential; a consent entry only grants"
                        + " access to a subject its authenticate entry proved");
    }

    /**
     * Found by EngineFuzzTest in CI: a few nested retries multiply into more paths than any heap
     * holds before the path bound was checked. The checker refuses such a flow, quickly.
     */
    @Test
    void aFlowWithTooManyPathsIsRefusedWithoutExhaustingMemory() {
        String step = "[\"retry\",3,[\"seq\",[\"retry\",3,[\"call\",\"identity.resolve\"]],"
                + "[\"retry\",3,[\"seq\",[\"screen\",\"password\"],[\"call\",\"password.verify\"]]],"
                + "[\"retry\",3,[\"seq\",[\"screen\",\"otp\"],[\"call\",\"totp.verify\"]]]]]";
        Flow flow = IrCodec.readFlow("[\"flow\",1,\"f\",1,[\"entry\",\"login\",\"authenticate\",[\"seq\","
                + "[\"step\",\"a\"," + step + "],[\"step\",\"b\"," + step + "],[\"succeed\"]]]]");
        long started = System.nanoTime();
        assertThat(errors(check(flow)))
                .anySatisfy(e -> assertThat(e).contains("paths").contains("split it"));
        assertThat((System.nanoTime() - started) / 1_000_000).as("milliseconds").isLessThan(5_000);
    }

    /** ADR 0026 review: consent in the sign in would ask too early and leave no consent entry. */
    @Test
    void consentBelongsOnTheConsentChallenge() {
        Flow flow = IrCodec.readFlow("[\"flow\",1,\"f\",1,[\"entry\",\"login\",\"authenticate\",[\"seq\","
                + "[\"step\",\"c\",[\"seq\",[\"screen\",\"identifier\"],[\"call\",\"identity.resolve\"],"
                + "[\"screen\",\"password\"],[\"call\",\"password.verify\"],[\"screen\",\"consent\"],"
                + "[\"call\",\"consent.record\"]]],[\"succeed\"]]]]");
        assertThat(errors(check(flow)))
                .containsExactly(
                        ": screen 'consent' belongs in a consent entry, not in this authenticate entry",
                        ": consent.record belongs in a consent entry, not in this authenticate entry");
    }

    /** Found by EngineFuzzTest: the method picker shown by a step has no options and no answer. */
    @Test
    void aStepCannotShowTheMethodPicker() {
        Flow flow = IrCodec.readFlow("[\"flow\",1,\"f\",1,[\"entry\",\"login\",\"authenticate\",[\"seq\","
                + "[\"step\",\"identify\",[\"seq\",[\"screen\",\"identifier\"],[\"call\",\"identity.resolve\"]]],"
                + "[\"step\",\"pick\",[\"seq\",[\"screen\",\"method_picker\"],[\"call\",\"password.verify\"]]],"
                + "[\"succeed\"]]]]");
        assertThat(errors(check(flow)))
                .contains(": 'method_picker' is shown by an 'alternative' for its options; a step cannot show it");
    }

    /** ADR 0029: a screen's path keeps the first segment of its default, so every flow reads alike. */
    @Test
    void screenPathsKeepTheirFirstSegment() {
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { path: /challenge/who, block: identifier }
                    credential: { path: /login, block: password }
                    second: { path: /challenge/mfa/totp, block: totp }
                    third: { path: /challenge/Bad, block: totp }
                """);
        assertThat(errors(check(flow)))
                .contains(
                        "tenant.yaml:5:23: screen 'identifier' takes a path of '/login' and up to three lowercase"
                                + " segments, such as '/login/name'",
                        "tenant.yaml:6:25: screen 'password' takes a path of '/challenge' and up to three"
                                + " lowercase segments, such as '/challenge/name'",
                        "tenant.yaml:8:20: screen 'otp' takes a path of '/challenge' and up to three lowercase"
                                + " segments, such as '/challenge/name'")
                .noneMatch(e -> e.startsWith("tenant.yaml:7:"));
    }

    /** Found by EngineFuzzTest: the registration screen is a credential write too. */
    @Test
    void aPasskeyRegistrationScreenNeedsAVerifiedSubjectFirst() {
        Flow flow = IrCodec.readFlow("[\"flow\",1,\"f\",1,[\"entry\",\"login\",\"authenticate\",[\"seq\","
                + "[\"step\",\"identify\",[\"seq\",[\"screen\",\"identifier\"],[\"call\",\"identity.resolve\"]]],"
                + "[\"step\",\"add\",[\"seq\",[\"screen\",\"passkey\",{\"register\":true}],"
                + "[\"call\",\"webauthn.assert\"]]],"
                + "[\"succeed\"]]]]");
        assertThat(errors(check(flow)))
                .contains(": screen 'passkey' acts for the signed in subject, which needs a subject a"
                        + " credential verified earlier in the flow, but shows first on a path");
    }

    @Test
    void aCredentialWriteNeedsAVerifiedSubjectFirst() {
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { block: identifier }
                    enroll: { block: add-passkey }
                    credential: { block: password }
                """);
        // The block is refused once, at the block: its screen and its handler are the same place (ADR 0030)
        assertThat(errors(check(flow)))
                .singleElement()
                .asString()
                .startsWith("tenant.yaml:6:22: screen 'passkey' acts for the signed in subject");
    }

    /** ADR 0032: the common case, the app if enrolled, a text if the switch is on, else email. */
    @Test
    void deliveredCodesAsASecondFactor() {
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { block: identifier }
                    credential: { block: password }
                    second-factor:
                      alternative:
                        app:   { if: user.has_totp, block: totp }
                        phone: { if: user.has_phone, block: sms-code }
                        email: { if: user.email_verified, block: email-code, with: { attempts: 5 } }
                """);
        Report report = check(flow);
        assertThat(report.errors()).isEmpty();
        // A password and an emailed code are one factor; with a texted code or the app, two
        assertThat(report.summary()).endsWith("assurance email+pwd (faye:1fa) to otp+pwd (faye:2fa)");
        assertThat(errors(check(flow, MFA)))
                .anySatisfy(e -> assertThat(e).contains("the tenant requires MFA, but a path succeeds with faye:1fa"));
    }

    @Test
    void addingAPhoneNeedsAVerifiedSubjectFirst() {
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { block: identifier }
                    phone: { block: add-phone }
                    credential: { block: password }
                """);
        assertThat(errors(check(flow)))
                .singleElement()
                .asString()
                .startsWith("tenant.yaml:6:21: screen 'phone_enroll' acts for the signed in subject");
    }

    /** ADR 0032: a QR code beside a password, and the approve entry that runs on the phone. */
    @Test
    void qrSignInWithItsApproveEntry() {
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { block: identifier }
                    sign-in:
                      alternative:
                        qr: { block: qr }
                        password: { block: password }
                approve:
                  steps:
                    who: { block: identifier }
                    prove: { block: password }
                    confirm: { block: qr-approve }
                """);
        assertThat(errors(check(flow))).isEmpty();
        assertThat(faye.ir.IrCodec.toJson(flow).get(1).asInt()).isEqualTo(2);
    }

    @Test
    void aQrCodeNeedsAnApproveEntry() {
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { block: identifier }
                    sign-in: { block: qr }
                """);
        assertThat(errors(check(flow)))
                .singleElement()
                .asString()
                .contains("screen 'qr' needs the flow to have a approve entry");
        assertThat(faye.ir.IrCodec.toJson(flow).get(1).asInt()).isEqualTo(1);
    }

    @Test
    void anApproveEntryEndsWithItsApproval() {
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { block: identifier }
                    credential: { block: password }
                approve:
                  steps:
                    who: { block: identifier }
                    confirm: { block: qr-approve }
                    prove: { block: password }
                """);
        assertThat(errors(check(flow)))
                .anySatisfy(e -> assertThat(e).contains("an approve entry ends with exactly one approval"))
                .anySatisfy(e -> assertThat(e).contains("screen 'qr_approve' acts for the signed in subject"));
        Flow without = Fixtures.yaml("tenant.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { block: identifier }
                    credential: { block: password }
                approve:
                  steps:
                    who: { block: identifier }
                    prove: { block: password }
                """);
        assertThat(errors(check(without)))
                .anySatisfy(e -> assertThat(e).contains("an approve entry ends with exactly one approval"));
    }

    @Test
    void anApprovalBelongsOnlyInApprove() {
        assertThatThrownBy(() -> Fixtures.yaml("tenant.yaml", """
                        flow: f
                        version: 1
                        login:
                          steps:
                            identify: { block: identifier }
                            credential: { block: password }
                            confirm: { block: qr-approve }
                        """)).hasMessageContaining("it is a step of 'approve'");
        assertThatThrownBy(() -> Fixtures.yaml("tenant.yaml", """
                        flow: f
                        version: 1
                        login:
                          steps:
                            identify: { block: identifier }
                            credential: { block: password }
                        approve:
                          steps:
                            who: { block: identifier }
                            prove: { block: password }
                            grant: { block: consent }
                            confirm: { block: qr-approve }
                        """))
                .hasMessageContaining("'approve' asks for no consent");
        // A QR code cannot approve a QR code
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { block: identifier }
                    credential: { block: password }
                approve:
                  steps:
                    who: { block: identifier }
                    prove: { block: qr }
                    confirm: { block: qr-approve }
                """);
        assertThat(errors(check(flow))).anySatisfy(e -> assertThat(e).contains("screen 'qr' belongs in a"));
    }

    /**
     * An emailed or texted code may stand in for the password; a code from an authenticator app is
     * only ever a second factor (ADR 0032).
     */
    @Test
    void deliveredCodesMayReplaceThePasswordButTotpMayNot() {
        Flow codes = Fixtures.yaml("tenant.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { block: identifier }
                    credential:
                      alternative:
                        password: { block: password }
                        email: { if: user.email_verified, block: email-code }
                        phone: { if: user.has_phone, block: sms-code }
                """);
        assertThat(errors(check(codes))).isEmpty();
        Flow totp = Fixtures.yaml("tenant.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { block: identifier }
                    credential:
                      alternative:
                        password: { block: password }
                        app: { block: totp }
                """);
        assertThat(errors(check(totp)))
                .anySatisfy(e -> assertThat(e).contains("totp.verify needs a subject a credential verified earlier"));
    }

    /** A tenant without SMS delivery has neither text message handler enabled (ADR 0032). */
    @Test
    void aTenantWithoutSmsDeliveryCannotNameATextedCode() {
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { block: identifier }
                    credential: { block: password }
                    second-factor: { block: sms-code }
                """);
        Report report = check(
                flow,
                new TenantPolicy(
                        false,
                        Set.of("identity.resolve", "password.verify", "email_code.verify", "totp.verify"),
                        null));
        assertThat(errors(report))
                .containsExactly("tenant.yaml:7:29: handler 'sms_code.verify' is not enabled for this tenant");
    }

    /** Without code delivery its screens are not registered, so the verifier refuses them. */
    @Test
    void aCodeScreenWithoutDeliveryIsUnknown() {
        Flow flow = Fixtures.conformanceFlow("delivered-codes");
        var noDelivery = faye.screens.BuiltinScreen.registry(faye.engine.testing.FixedIssuers.all().stream()
                .filter(i -> !i.screen().equals("code_sent") && !i.screen().equals("phone_enroll"))
                .toList());
        assertThat(Checker.check(flow, Fixtures.handlers(), noDelivery, TenantPolicy.DEFAULT).errors().stream()
                        .map(Finding::toString))
                .anySatisfy(e -> assertThat(e).contains("code_sent"));
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
        Flow flow = IrCodec.readFlow("[\"flow\",1,\"f\",1,[\"entry\",\"login\",\"authenticate\",[\"seq\","
                + "[\"step\",\"identify\",[\"seq\",[\"screen\",\"identifier\",{\"pasky\":true}],"
                + "[\"call\",\"identity.resolve\"],[\"call\",\"password.verify\"]]],"
                + "[\"succeed\"]]]]");
        assertThat(errors(check(flow)))
                .singleElement()
                .asString()
                .startsWith(": screen 'identifier' has no option 'pasky'; it takes ");
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
                    identify: { block: identifier }
                    credential:
                      if: { or: [host.prompt, { has: [host.acr_values, "faye:2fa"] }, host.context.origin] }
                      block: password
                """);
        assertThat(errors(check(flow)).stream().filter(e -> e.contains("host contract")))
                .singleElement()
                .satisfies(e ->
                        assertThat(e).startsWith("tenant.yaml:7:11: 'host.prompt' is not part of the host contract"));
    }

    @Test
    void aClientPathOutsideTheContractIsRefusedAtPublish() {
        Flow flow = Fixtures.yaml("tenant.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { block: identifier }
                    credential:
                      if: { or: [client.tier, client.first_party, client.metadata.risk] }
                      block: password
                """);
        assertThat(errors(check(flow)).stream().filter(e -> e.contains("client contract")))
                .singleElement()
                .satisfies(e -> assertThat(e).contains("'client.tier' is not part of the client contract"));
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

    static Flow hooks(int n, int retries) {
        String[] hooks = new String[n];
        for (int i = 0; i < n; i++) {
            hooks[i] = "[\"hook\",\"h" + i + "\",\"sha256:" + "0".repeat(63) + i + "\"]";
        }
        String body = "[\"seq\"," + String.join(",", hooks) + "]";
        if (retries > 1) body = "[\"retry\"," + retries + "," + body + "]";
        return IrCodec.readFlow("[\"flow\",1,\"f\",1,[\"entry\",\"login\",\"authenticate\","
                + "[\"seq\",[\"step\",\"s\"," + body + "]]]]");
    }

    static Report checkWithTimeout(Flow flow, int timeoutMs) {
        HookDef def = new HookDef("export default () => ({})", List.of(), timeoutMs);
        return Checker.check(
                flow,
                Fixtures.handlers(),
                Fixtures.screens(),
                TenantPolicy.DEFAULT,
                hash -> java.util.Optional.of(def));
    }

    @Test
    void anEntryRunsAtMost32HooksAcrossItsRequests() {
        // Three retries of 10 around one hook each, plus two plain hooks, is 32 runs: at the limit
        // (still enumerable; a retry around a seq of hooks has a path per outcome per attempt)
        assertThat(checkWithTimeout(
                                IrCodec.readFlow("[\"flow\",1,\"f\",1,[\"entry\",\"login\",\"authenticate\","
                                        + "[\"seq\",[\"step\",\"s\",[\"seq\","
                                        + "[\"retry\",10," + hook(1) + "],"
                                        + "[\"retry\",10," + hook(2) + "],"
                                        + "[\"retry\",10," + hook(3) + "],"
                                        + hook(4) + "," + hook(5) + "]]]]]"),
                                1)
                        .errors())
                .isEmpty();
        // retry 10 around a seq of 4 hooks is 40: one finding at the attachment that crosses the sum
        assertThat(checkWithTimeout(hooks(4, 10), 1).errors().stream()
                        .map(Finding::message)
                        .filter(m -> m.contains("more than 32 hooks"))
                        .toList())
                .hasSize(1);
    }

    static String hook(int i) {
        return "[\"hook\",\"h" + i + "\",\"sha256:" + "0".repeat(63) + i + "\"]";
    }

    @Test
    void anEntryTakesAtMost5000MsOfHookTimeAcrossItsRequests() {
        // 4 hooks of 1,250 ms is 5,000: at the limit
        assertThat(checkWithTimeout(hooks(4, 1), 1250).errors()).isEmpty();
        // 3 hooks of 2,000 ms is 6,000: over
        assertThat(checkWithTimeout(hooks(3, 1), 2000).errors().stream()
                        .map(Finding::message)
                        .filter(m -> m.contains("more than 5000 ms"))
                        .toList())
                .hasSize(1);
    }
}
