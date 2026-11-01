import com.diffplug.gradle.spotless.SpotlessExtension
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    `java-library`
    id("net.ltgt.errorprone")
    id("com.diffplug.spotless")
    checkstyle
}

group = "faye"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// The libs catalog of whichever build applies this plugin (the product build itself, or a
// consuming build that includes it); all point at the product catalog, so versions are identical.
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

// The repo root: the first ancestor that holds the product's schema. Works for the standalone
// build, for a consuming composite build, and in the Docker build, which preserves the layout.
val repoRoot = generateSequence(rootDir) { it.parentFile }
    .first { it.resolve("src/schema/ir.schema.json").isFile }
// The single checkstyle config, shared by every build that applies this plugin
val checkstyleDir = generateSequence(rootDir.parentFile) { it.parentFile }
    .map { it.resolve("src/config/checkstyle") }
    .first { it.isDirectory }

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    // JDK 21 needs the -XD flag for NullAway's JSpecify mode; later javac ignores it
    options.compilerArgs.addAll(
        listOf("-Xlint:all,-processing,-serial", "-Werror", "-parameters", "-XDaddTypeAnnotationsToSymbol=true"),
    )
    // Error Prone runs NullAway only: every type is non-null unless annotated @Nullable (ADR 0002)
    options.errorprone {
        disableAllChecks = true
        check("NullAway", CheckSeverity.ERROR)
        option("NullAway:AnnotatedPackages", "faye")
        option("NullAway:JSpecifyMode", "true")
    }
}

configure<SpotlessExtension> {
    java {
        palantirJavaFormat(catalog.findVersion("palantir-java-format").get().requiredVersion)
        removeUnusedImports()
        forbidWildcardImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// Checkstyle lints what the formatter cannot fix; any violation fails the build
configure<CheckstyleExtension> {
    toolVersion = catalog.findVersion("checkstyle").get().requiredVersion
    configDirectory = checkstyleDir
    maxWarnings = 0
}

dependencies {
    "implementation"(platform(catalog.findLibrary("jackson-bom").get()))
    "testImplementation"(platform(catalog.findLibrary("junit-bom").get()))
    "implementation"(catalog.findLibrary("jspecify").get())
    "errorprone"(catalog.findLibrary("errorprone-core").get())
    "errorprone"(catalog.findLibrary("nullaway").get())
    "testImplementation"(catalog.findLibrary("junit-jupiter").get())
    "testImplementation"(catalog.findLibrary("assertj").get())
    "testImplementation"(catalog.findLibrary("jazzer-junit").get())
    "testRuntimeOnly"(catalog.findLibrary("junit-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // schema/ and conformance/ live beside this build at src/
    systemProperty("faye.repoRoot", repoRoot.absolutePath)
    inputs.dir(repoRoot.resolve("src/schema")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(repoRoot.resolve("src/conformance")).withPathSensitivity(PathSensitivity.RELATIVE)
    // Fuzz harnesses replay their inputs in every build; JAZZER_FUZZ=1 with one --tests filter
    // fuzzes that harness instead, for JAZZER_MAX_DURATION (ADR 0007, deploy/fuzz.sh)
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    listOf("JAZZER_FUZZ", "JAZZER_MAX_DURATION").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
    System.getenv("JAZZER_MAX_DURATION")?.let { systemProperty("jazzer.max_duration", it) }
}
