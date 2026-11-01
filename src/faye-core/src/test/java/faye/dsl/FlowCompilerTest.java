package faye.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import faye.ir.Call;
import faye.ir.Choose;
import faye.ir.Flow;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class FlowCompilerTest {

    /** The blocks the app's identity module implements, as faye.handlers' BuiltinBlock does. */
    record TestBlock(
            String id,
            String title,
            String handler,
            String screen,
            BlockSpec.Role role,
            Map<String, Object> fixed,
            Map<String, BlockSpec.Setting> settings)
            implements BlockSpec {}

    static final BlockSpec.Setting THREE = new BlockSpec.Setting(3, BlockSpec.Setting.Target.ATTEMPTS);

    static final Map<String, BlockSpec> BLOCKS = Map.of(
            "identifier",
            new TestBlock(
                    "identifier",
                    "Identifier",
                    "identity.resolve",
                    "identifier",
                    BlockSpec.Role.IDENTIFIER,
                    Map.of(),
                    Map.of(
                            "passkey",
                            new BlockSpec.Setting(false, BlockSpec.Setting.Target.OPTION),
                            "password",
                            new BlockSpec.Setting(false, BlockSpec.Setting.Target.PASSWORD),
                            "attempts",
                            THREE)),
            "password",
            new TestBlock(
                    "password",
                    "Password",
                    "password.verify",
                    "password",
                    BlockSpec.Role.PASSWORD,
                    Map.of(),
                    Map.of("attempts", THREE)),
            "passkey",
            new TestBlock(
                    "passkey",
                    "Passkey",
                    "webauthn.assert",
                    "passkey",
                    BlockSpec.Role.CREDENTIAL,
                    Map.of(),
                    Map.of("attempts", new BlockSpec.Setting(1, BlockSpec.Setting.Target.ATTEMPTS))),
            "add-passkey",
            new TestBlock(
                    "add-passkey",
                    "Add passkey",
                    "webauthn.register",
                    "passkey",
                    BlockSpec.Role.ACTION,
                    Map.of("register", true),
                    Map.of()),
            "totp",
            new TestBlock(
                    "totp",
                    "TOTP",
                    "totp.verify",
                    "otp",
                    BlockSpec.Role.CREDENTIAL,
                    Map.of(),
                    Map.of("attempts", THREE)),
            "enroll-totp",
            new TestBlock(
                    "enroll-totp",
                    "Enroll TOTP",
                    "totp.enroll",
                    "otp_enroll",
                    BlockSpec.Role.CREDENTIAL,
                    Map.of(),
                    Map.of("attempts", THREE)),
            "consent",
            new TestBlock(
                    "consent", "Consent", "consent.record", "consent", BlockSpec.Role.CONSENT, Map.of(), Map.of()));

    /** What the app tells the compiler about consent steps and its blocks, as its catalogs say. */
    static final FlowCompiler.Options DSL =
            new FlowCompiler.Options(Map.of(), Set.of("consent"), Set.of("consent.record"), BLOCKS);

    static Flow compile(String origin, String yaml) {
        return FlowCompiler.compile(origin, yaml, DSL);
    }

    static final Path ROOT = Path.of(System.getProperty("faye.repoRoot"));
    static final Path TENANT_FLOWS = ROOT.resolve("apps/server/faye-app/src/main/resources/flows");
    static final Path RESOURCES = ROOT.resolve("src/faye-core/src/test/resources");

    static final String DEFAULT_LOGIN = """
            flow: default-login
            version: 1

            login:
              steps:
                identify:
                  block: identifier
                credential:
                  block: password
                  with: { attempts: 1 }
            """;

    @Test
    void compilesToTheConformanceIr() throws Exception {
        var expected = IrCodec.readFlow(Files.readString(ROOT.resolve("src/conformance/flows/default-login.json")));
        assertThat(IrCodec.canonicalJson(compile("default-login.yaml", DEFAULT_LOGIN)))
                .isEqualTo(IrCodec.canonicalJson(expected));
    }

    @Test
    void recordsSourcePositions() {
        var flow = compile("default-login.yaml", DEFAULT_LOGIN);
        var login = (Seq) Objects.requireNonNull(flow.entries().get("login")).body();
        var credential = (Step) login.children().get(1);
        var body = (Seq) credential.body();

        assertThat(login.source()).isEqualTo(new SourceRef("default-login.yaml", 4, 1));
        assertThat(credential.source()).isEqualTo(new SourceRef("default-login.yaml", 8, 5));
        // A block's screen and call are where the flow names it
        assertThat(((Screen) body.children().get(0)).source().line()).isEqualTo(9);
        assertThat(((Call) body.children().get(1)).source().line()).isEqualTo(9);
    }

    /** The step that records consent, and every step after it, is the consent entry. */
    @Test
    void stepsFromConsentOnLowerToTheConsentEntry() {
        var flow = compile("f.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    resolve: { block: identifier }
                    consent:
                      if: { not: client.first_party }
                      block: consent
                    after: { set: { done: true } }
                """);
        assertThat(IrCodec.canonicalJson(flow))
                .isEqualTo("[\"flow\",1,\"f\",1,[\"entry\",\"login\",\"authenticate\",[\"seq\","
                        + "[\"step\",\"resolve\",[\"seq\",[\"screen\",\"identifier\"],"
                        + "[\"call\",\"identity.resolve\"]]],"
                        + "[\"succeed\"]]],"
                        + "[\"entry\",\"consent\",\"consent\",[\"seq\",[\"step\",\"consent\",[\"when\","
                        + "{\"not\":\"client.first_party\"},[\"seq\","
                        + "[\"screen\",\"consent\"],[\"call\",\"consent.record\"]]]],"
                        + "[\"step\",\"after\",[\"set\",\"done\",true]],[\"succeed\"]]]]");
        // The consent entry points at the step that starts it
        assertThat(Objects.requireNonNull(flow.entries().get("consent")).body().source())
                .isEqualTo(new SourceRef("f.yaml", 6, 5));
    }

    /** The Consent block starts consent; a literal that only says consent.record does not. */
    @Test
    void theConsentBlockStartsConsentAndLiteralsDoNot() {
        var flow = compile("f.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    resolve: { block: identifier }
                    note: { set: { use: consent.record } }
                    ask: { block: consent }
                """);
        assertThat(flow.entries()).containsOnlyKeys("login", "consent");
        assertThat(IrCodec.canonicalJson(flow))
                .contains("[\"entry\",\"consent\",\"consent\",[\"seq\",[\"step\",\"ask\"");
    }

    /** A body's path is the option of the screen it shows. */
    @Test
    void pathIsTheScreensOption() {
        var flow = compile("f.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { path: /login, block: identifier }
                    credential:
                      path: /challenge/password
                      block: password
                """);
        assertThat(IrCodec.canonicalJson(flow))
                .contains("[\"screen\",\"identifier\",{\"path\":\"/login\"}]")
                .contains("[\"screen\",\"password\",{\"path\":\"/challenge/password\"}]");
    }

    /** A step names its block; the keys that took a block apart say what to write instead. */
    @Test
    void keysThatTookABlockApartSayWhatToWrite() {
        assertThatThrownBy(() -> compile("f.yaml", """
                        flow: f
                        version: 1
                        login:
                          steps:
                            identify: { block: identifier }
                            credential:
                              retry: 5
                              screen: password
                              use: password.verify
                            other: { block: sms }
                            second: { block: totp, with: { attempts: two, colour: red } }
                        """))
                .isInstanceOfSatisfying(
                        DslException.class,
                        e -> assertThat(e.diagnostics().stream().map(Diagnostic::toString))
                                .containsExactly(
                                        "f.yaml:7:7: attempts are a setting of a block:"
                                                + " write 'with: { attempts: 5 }'",
                                        "f.yaml:8:7: a block shows its own screen;"
                                                + " change what it offers under 'with'",
                                        "f.yaml:9:7: a step names its block, which brings its handler:"
                                                + " write 'block: password'",
                                        "f.yaml:10:21: unknown block 'sms'; the blocks are add-passkey, consent,"
                                                + " enroll-totp,"
                                                + " identifier, passkey, password, totp",
                                        "f.yaml:11:46: 'attempts' is a whole number of 1 or more",
                                        "f.yaml:11:51: a TOTP block has no setting 'colour'; it has 'attempts'"));
    }

    @Test
    void aPathIsSetOnceOnABlock() {
        assertThatThrownBy(() -> compile("f.yaml", """
                        flow: f
                        version: 1
                        login:
                          steps:
                            resolve: { block: identifier }
                            note: { set: { a: 1 }, path: /challenge/x }
                        """))
                .isInstanceOf(DslException.class)
                .hasMessageContaining("f.yaml:6:28: a Set block takes 'if', 'before', 'set', 'after', not 'path'");
    }

    /** A flow whose steps never record consent has no consent entry. */
    @Test
    void noConsentStepNoConsentEntry() {
        var flow = compile("f.yaml", DEFAULT_LOGIN);
        assertThat(flow.entries()).containsOnlyKeys("login");
    }

    /** A block lowers to its screen, with its fixed options, and its handler; a one part body has no seq. */
    @Test
    void aBlockLowersToItsScreenAndHandler() {
        var flow = compile("f.yaml", """
                flow: f
                version: 2
                login:
                  steps:
                    resolve: { block: identifier }
                    note: { set: { a: 1 } }
                    add: { block: add-passkey }
                """);
        assertThat(IrCodec.canonicalJson(flow))
                .isEqualTo("[\"flow\",1,\"f\",2,[\"entry\",\"login\",\"authenticate\",[\"seq\","
                        + "[\"step\",\"resolve\",[\"seq\",[\"screen\",\"identifier\"],"
                        + "[\"call\",\"identity.resolve\"]]],"
                        + "[\"step\",\"note\",[\"set\",\"a\",1]],"
                        + "[\"step\",\"add\",[\"seq\",[\"screen\",\"passkey\",{\"register\":true}],"
                        + "[\"call\",\"webauthn.register\"]]],[\"succeed\"]]]]");
    }

    /** The Identifier's password view verifies the password with the identifier, on one screen. */
    @Test
    void theIdentifiersPasswordViewVerifiesThePassword() {
        var flow = compile("f.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    identify:
                      block: identifier
                      with: { password: true }
                """);
        assertThat(IrCodec.canonicalJson(flow))
                .isEqualTo("[\"flow\",1,\"f\",1,[\"entry\",\"login\",\"authenticate\",[\"seq\","
                        + "[\"step\",\"identify\",[\"retry\",3,[\"seq\","
                        + "[\"screen\",\"identifier\",{\"password\":true}],"
                        + "[\"call\",\"identity.resolve\"],[\"call\",\"password.verify\"]]]],"
                        + "[\"succeed\"]]]]");
    }

    /** A tenant's defaults replace the block's; the flow's own settings still win. */
    @Test
    void tenantDefaultsReplaceTheBlocksAndTheFlowWins() {
        var tenant = DSL.withDefaults(Map.of("password", Map.of("attempts", 5), "identifier", Map.of("passkey", true)));
        String flow = """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { block: identifier }
                    credential: { block: password }
                """;
        assertThat(IrCodec.canonicalJson(FlowCompiler.compile("f.yaml", flow, tenant)))
                .contains("[\"screen\",\"identifier\",{\"passkey\":true}]")
                .contains("[\"retry\",5,");
        assertThat(IrCodec.canonicalJson(FlowCompiler.compile(
                        "f.yaml",
                        flow.replace("{ block: password }", "{ block: password, with: { attempts: 2 } }"),
                        tenant)))
                .contains("[\"retry\",2,");
        // Asking for the password turns off the autofill a default turned on, not one the flow did
        String both = """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { block: identifier, with: { password: true } }
                """;
        assertThat(IrCodec.canonicalJson(FlowCompiler.compile("f.yaml", both, tenant)))
                .contains("[\"screen\",\"identifier\",{\"password\":true}]");
        assertThatThrownBy(() -> FlowCompiler.compile(
                        "f.yaml", both.replace("password: true", "password: true, passkey: true"), tenant))
                .hasMessageContaining("asks for the password with no other option, not 'passkey'");
    }

    /** Hooks run before and after a block, once around its attempts, and inside its condition. */
    @Test
    void beforeAndAfterHooksWrapTheBlock() {
        var compiled = FlowCompiler.compileWithHooks("f.yaml", """
                flow: f
                version: 1
                hooks:
                  risk: { source: "export default () => ({})" }
                login:
                  steps:
                    identify: { block: identifier, after: risk }
                    credential:
                      if: user.exists
                      before: risk
                      with: { attempts: 2 }
                      block: password
                """, name -> Optional.empty(), DSL);
        String hook =
                "[\"hook\",\"risk\",\"" + compiled.hooks().keySet().iterator().next() + "\"]";
        assertThat(IrCodec.canonicalJson(compiled.flow()))
                .contains("[\"step\",\"identify\",[\"seq\",[\"screen\",\"identifier\"],"
                        + "[\"call\",\"identity.resolve\"]," + hook + "]]")
                .contains("[\"step\",\"credential\",[\"when\",\"user.exists\",[\"seq\"," + hook
                        + ",[\"retry\",2,[\"seq\",[\"screen\",\"password\"],[\"call\",\"password.verify\"]]]]]]");
    }

    @Test
    void anOptionsIfIsItsGuardAndAStepsIfIsAWhen() {
        var flow = compile("f.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { block: identifier }
                    mfa:
                      if: user.mfa_required
                      alternative:
                        totp: { if: user.has_totp, block: totp }
                        password: { block: password }
                """);
        var step = (Step)
                ((Seq) Objects.requireNonNull(flow.entries().get("login")).body())
                        .children()
                        .get(1);
        var when = (When) step.body();
        var choose = (Choose) when.body();
        assertThat(choose.options().get(0).guard()).isEqualTo(new Predicate.Path("user.has_totp"));
        assertThat(choose.options().get(0).source().line()).isEqualTo(9);
        assertThat(choose.options().get(1).guard()).isNull();
    }

    @Test
    void aliasedNamespacesLowerToTheirNamespace() {
        var flow = FlowCompiler.compileWithHooks(
                        "f.yaml",
                        """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { block: identifier }
                    a:
                      if: { or: [hydra.skip, { has: [hydra.acr_values, faye:2fa] }, { exists: hydra.login_hint },
                                 { eq: [user.note, hydra.literal] }] }
                      block: password
                """,
                        name -> Optional.empty(),
                        new FlowCompiler.Options(Map.of("hydra", "host"), Set.of(), Set.of(), DSL.blocks()))
                .flow();
        assertThat(IrCodec.canonicalJson(flow))
                .contains("{\"or\":[\"host.skip\",{\"has\":[\"host.acr_values\",\"faye:2fa\"]},"
                        + "{\"exists\":\"host.login_hint\"},{\"eq\":[\"user.note\",\"hydra.literal\"]}]}");
    }

    @Test
    void theCompilerKnowsNoHostSpelling() {
        assertThatThrownBy(() -> compile("f.yaml", """
                        flow: f
                        version: 1
                        login:
                          steps:
                            identify: { block: identifier }
                            a:
                              if: hydra.skip
                              block: password
                        """)).isInstanceOf(DslException.class);
    }

    @Test
    void reportsYamlSyntaxErrors() {
        assertThatThrownBy(() -> compile("broken.yaml", "flow: [unclosed\n"))
                .isInstanceOfSatisfying(
                        DslException.class,
                        e -> assertThat(e.diagnostics().get(0).source().origin())
                                .isEqualTo("broken.yaml"));
    }

    /**
     * Fuzz finding: SnakeYAML reads YAML 1.1, which breaks lines at NEL, LS and PS, so {@code hi<NEL>mfa:
     * false} was two keys on what an editor shows as one line, and diagnostics pointed past its last line.
     */
    @Test
    void refusesALineBreakOnlyYaml11Sees() {
        for (String hidden : List.of("\u0085", "\u2028", "\u2029")) {
            String yaml = "flow: f\r\nversion: 1\ndescription: hi" + hidden + "mfa: false\nlogin: {}\n";
            assertThatThrownBy(() -> compile("f.yaml", yaml))
                    .isInstanceOfSatisfying(
                            DslException.class,
                            e -> assertThat(e.diagnostics()).singleElement().satisfies(d -> {
                                assertThat(d.source()).isEqualTo(new SourceRef("f.yaml", 3, 16));
                                assertThat(d.message()).contains("YAML 1.1");
                            }));
        }
    }

    @Test
    void refusesYamlPastItsLimitsAsADiagnostic() {
        String deep = "a: ".repeat(200) + "b\n";
        assertThatThrownBy(() -> compile("deep.yaml", deep)).isInstanceOf(DslException.class);
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
        var compiled = FlowCompiler.compileWithHooks(
                yaml.getFileName().toString(),
                Files.readString(yaml),
                file -> {
                    try {
                        return Optional.of(Files.readString(TENANT_FLOWS.resolve(file)));
                    } catch (IOException e) {
                        return Optional.empty();
                    }
                },
                WITH_APPROVE);
        String actual = IrCodec.display(compiled.flow()) + "\n";
        Path golden = RESOURCES.resolve("golden/" + name + ".ir");
        if ("1".equals(System.getenv("FAYE_REGENERATE"))) {
            Files.writeString(golden, actual);
            return;
        }
        assertThat(golden)
                .as("golden missing; run FAYE_REGENERATE=1 ./gradlew :faye-core:test")
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
        assertThatThrownBy(() -> compile(origin, yaml))
                .isInstanceOfSatisfying(
                        DslException.class,
                        e -> assertThat(e.diagnostics())
                                .extracting(d -> d.toString().substring(origin.length() + 1))
                                .containsExactlyElementsOf(expected));
    }

    /** ADR 0032: {@code approve} lowers to an approve entry, and makes the flow IR format 2. */
    @Test
    void approveLowersToAnApproveEntryInFormat2() {
        Flow flow = FlowCompiler.compile("f.yaml", """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { block: identifier }
                    credential: { block: password }
                approve:
                  steps:
                    who: { block: identifier, with: { password: true } }
                    confirm: { block: qr-approve }
                """, WITH_APPROVE);
        assertThat(flow.entriesOf(faye.ir.Entry.Kind.APPROVE)).containsExactly("approve");
        assertThat(faye.ir.IrCodec.toJson(flow).get(1).asInt()).isEqualTo(2);
        assertThat(faye.ir.IrCodec.toJson(compile("g.yaml", """
                                flow: g
                                version: 1
                                login:
                                  steps:
                                    identify: { block: identifier }
                                    credential: { block: password }
                                """)).get(1).asInt())
                .isEqualTo(1);
    }

    /** The blocks, with those of delivered codes and QR code sign in (ADR 0032). */
    static final FlowCompiler.Options WITH_APPROVE = withApprove();

    private static FlowCompiler.Options withApprove() {
        Map<String, BlockSpec> blocks = new java.util.HashMap<>(BLOCKS);
        blocks.put(
                "email-code",
                new TestBlock(
                        "email-code",
                        "Email code",
                        "email_code.verify",
                        "code_sent",
                        BlockSpec.Role.CREDENTIAL,
                        Map.of("channel", "email"),
                        Map.of("attempts", THREE)));
        blocks.put(
                "sms-code",
                new TestBlock(
                        "sms-code",
                        "Text message code",
                        "sms_code.verify",
                        "code_sent",
                        BlockSpec.Role.CREDENTIAL,
                        Map.of("channel", "sms"),
                        Map.of("attempts", THREE)));
        blocks.put(
                "add-phone",
                new TestBlock(
                        "add-phone",
                        "Add phone",
                        "phone.enroll",
                        "phone_enroll",
                        BlockSpec.Role.ACTION,
                        Map.of(),
                        Map.of("attempts", THREE)));
        blocks.put(
                "qr",
                new TestBlock(
                        "qr",
                        "QR code",
                        "qr.redeem",
                        "qr",
                        BlockSpec.Role.CREDENTIAL,
                        Map.of(),
                        Map.of("attempts", THREE)));
        blocks.put(
                "qr-approve",
                new TestBlock(
                        "qr-approve",
                        "Approve QR code",
                        "qr.approve",
                        "qr_approve",
                        BlockSpec.Role.APPROVE,
                        Map.of(),
                        Map.of()));
        return new FlowCompiler.Options(Map.of(), Set.of("consent"), Set.of("consent.record"), blocks);
    }

    /** A login, and an approve section whose first step is the identifier. */
    static final String APPROVE_HEAD = """
            flow: f
            version: 1
            login:
              steps:
                identify: { block: identifier }
            approve:
              steps:
                who: { block: identifier }
            """;

    @Test
    void approveIsWhereTheApprovalGoes() {
        Map<String, String> cases = new java.util.LinkedHashMap<>();
        cases.put("""
                flow: f
                version: 1
                login:
                  steps:
                    identify: { block: identifier }
                    confirm: { block: qr-approve }
                """, "it is a step of 'approve'");
        cases.put(APPROVE_HEAD + "    grant: { block: consent }\n", "'approve' asks for no consent");
        String choice = """
                    any:
                      alternative:
                        a: { block: qr-approve }
                        b: { block: password }
                """;
        cases.put(APPROVE_HEAD + choice, "an option is one credential block");
        cases.put(APPROVE_HEAD + "    g:\n      steps:\n        c: { block: qr-approve }\n", "a step of its own");
        String loginOnly = """
                flow: f
                version: 1
                login:
                  steps:
                    identify: { block: identifier }
                """;
        cases.put(loginOnly + "approve: {}\n", "'approve' needs a step");
        cases.put(loginOnly + "approve: []\n", "a mapping with 'steps'");
        cases.put(loginOnly + "approve:\n  steps: []\n  other: 1\n", "unknown key 'other'");
        cases.forEach((yaml, message) -> assertThatThrownBy(() -> FlowCompiler.compile("f.yaml", yaml, WITH_APPROVE))
                .as(yaml)
                .isInstanceOf(DslException.class)
                .hasMessageContaining(message));
    }
}
