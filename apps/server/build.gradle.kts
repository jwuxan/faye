import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    alias(appLibs.plugins.spring.boot) apply false
    alias(libs.plugins.spotless)
}

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

// Catalog accessors are not in scope inside subprojects {}
val appCatalog = extensions.getByType<VersionCatalogsExtension>().named("appLibs")

subprojects {
    plugins.withId("java-library") {
        dependencies {
            // Boot's BOM manages the application libraries (starters, Flyway, spring-security-crypto)
            "implementation"(platform(appCatalog.findLibrary("spring-boot-bom").get()))
        }
    }
}
