import com.diffplug.gradle.spotless.SpotlessExtension
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.errorprone) apply false
    alias(libs.plugins.spotless)
}

val catalog = libs
val repoRoot = rootDir.parentFile

repositories {
    mavenCentral()
}

// Formatting is not negotiable: `check` runs spotlessCheck, `spotlessApply` fixes it (ADR 0003)
spotless {
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        // Version catalog accessors read better on one line
        ktlint().editorConfigOverride(mapOf("ktlint_standard_chain-method-continuation" to "disabled"))
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "net.ltgt.errorprone")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "checkstyle")

    group = "faye"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

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
            palantirJavaFormat(catalog.versions.palantir.java.format.get())
            removeUnusedImports()
            forbidWildcardImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    // Checkstyle lints what the formatter cannot fix; any violation fails the build
    configure<CheckstyleExtension> {
        toolVersion = catalog.versions.checkstyle.get()
        configDirectory = rootProject.file("config/checkstyle")
        maxWarnings = 0
    }

    dependencies {
        "implementation"(platform(catalog.spring.boot.bom))
        "implementation"(platform(catalog.jackson.bom))
        "implementation"(catalog.jspecify)
        "errorprone"(catalog.errorprone.core)
        "errorprone"(catalog.nullaway)
        "testImplementation"(catalog.junit.jupiter)
        "testImplementation"(catalog.assertj)
        "testImplementation"(catalog.jazzer.junit)
        "testRuntimeOnly"(catalog.junit.launcher)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // schema/ and conformance/ live outside the Gradle build
        systemProperty("faye.repoRoot", repoRoot.absolutePath)
        inputs.dir(repoRoot.resolve("schema")).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.dir(repoRoot.resolve("conformance")).withPathSensitivity(PathSensitivity.RELATIVE)
        // Fuzz harnesses replay their inputs in every build; JAZZER_FUZZ=1 with one --tests filter
        // fuzzes that harness instead, for JAZZER_MAX_DURATION (ADR 0007, deploy/fuzz.sh)
        jvmArgs("-XX:+EnableDynamicAgentLoading")
        listOf("JAZZER_FUZZ", "JAZZER_MAX_DURATION").forEach { name ->
            System.getenv(name)?.let { environment(name, it) }
        }
        System.getenv("JAZZER_MAX_DURATION")?.let { systemProperty("jazzer.max_duration", it) }
    }
}
