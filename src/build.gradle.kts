plugins {
    alias(libs.plugins.spotless)
}

repositories {
    mavenCentral()
}

// Formatting is not negotiable: `check` runs spotlessCheck, `spotlessApply` fixes it (ADR 0003)
spotless {
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts", "build-logic/*.gradle.kts", "build-logic/src/**/*.gradle.kts")
        // Version catalog accessors read better on one line
        ktlint().editorConfigOverride(mapOf("ktlint_standard_chain-method-continuation" to "disabled"))
    }
}
