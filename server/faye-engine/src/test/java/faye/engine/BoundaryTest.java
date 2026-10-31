package faye.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The core (the IR, the DSL, the engine and the checker) knows no authorization server and no
 * screen: it names neither in its sources, and depends on no host, screen or credential module
 * (charter principle 3, ADR 0024 invariant 4, ADR 0025).
 */
class BoundaryTest {

    private static final Path SERVER = Path.of(System.getProperty("faye.repoRoot"), "server");
    private static final List<String> CORE = List.of("faye-ir", "faye-dsl", "faye-engine", "faye-check");
    private static final List<String> OUTSIDE = List.of(
            ":faye-host-hydra",
            ":faye-host-mock",
            ":faye-screens",
            ":faye-clients",
            ":faye-identity",
            ":faye-hooks",
            ":faye-app");

    /** The host contract is the one place the protocol's parameter names are written down. */
    private static final String CONTRACT = "HostParams.java";

    @Test
    void theCoreNamesNoHostAndNoScreen() {
        List<String> found = new ArrayList<>();
        for (Path file : sources()) {
            String text = read(file);
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.contains("hydra")) found.add(file + ": hydra");
            if (lower.contains("otpauth")) found.add(file + ": otpauth");
            // The authorization server's clients are the admin console's business (faye-clients)
            if (text.contains("ClientDirectory") || text.contains("redirectUris")) {
                found.add(file + ": the authorization server's client registry");
            }
            if (text.contains("PASSKEY_ALGORITHMS")) found.add(file + ": PASSKEY_ALGORITHMS");
            if (text.contains("\"Faye\"")) found.add(file + ": \"Faye\"");
            // Screens and handlers say what they need through the catalogs (ScreenSpec, HandlerMeta)
            if (Pattern.compile("\"(consent\\.record|register|passkey|otp_enroll|identifier|password)\"")
                    .matcher(text)
                    .find()) {
                found.add(file + ": a built in screen, option or handler by name");
            }
            // Tenant and preview come typed from the host; claims are the host's (ADR 0025)
            if (text.contains("\"metadata\"")) found.add(file + ": reads client metadata");
            if (Pattern.compile("\"(faye_flow_id|email_verified)\"|id_?token", Pattern.CASE_INSENSITIVE)
                    .matcher(text)
                    .find()) {
                found.add(file + ": token claims");
            }
            if (!file.endsWith(CONTRACT)
                    && Pattern.compile("\"(login_hint|requested_scope|ui_locales)\"")
                            .matcher(text)
                            .find()) {
                found.add(file + ": a host parameter outside the host contract");
            }
        }
        assertThat(found).isEmpty();
    }

    @Test
    void theCoreDependsOnNoModuleAroundIt() {
        List<String> found = new ArrayList<>();
        for (String module : CORE) {
            for (String line :
                    read(SERVER.resolve(module).resolve("build.gradle.kts")).split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.matches("(api|implementation|compileOnly|runtimeOnly)\\(.*")) continue;
                OUTSIDE.stream()
                        .filter(m -> trimmed.contains("\"" + m + "\""))
                        .forEach(m -> found.add(module + " depends on " + m));
            }
        }
        assertThat(found).isEmpty();
    }

    private static List<Path> sources() {
        List<Path> out = new ArrayList<>();
        for (String module : CORE) {
            try (Stream<Path> files = Files.walk(SERVER.resolve(module).resolve("src/main"))) {
                files.filter(f -> f.toString().endsWith(".java")).forEach(out::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertThat(out).isNotEmpty();
        return out;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
