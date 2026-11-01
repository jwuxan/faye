plugins {
    id("faye.library-conventions")
    `java-test-fixtures`
}

dependencies {
    api(project(":faye-core"))
    api(libs.jackson.annotations)
    implementation(libs.jackson.databind)
    // Verifier checks call args against each handler's args schema, as IrCodec checks the IR (ADR 0024)
    implementation(libs.json.schema.validator)
    implementation(libs.slf4j.api)
    // Platforms do not reach the fixtures source set
    testFixturesImplementation(platform(libs.jackson.bom))
    testFixturesImplementation(libs.jspecify)
    // SPI contract kits are abstract JUnit tests an adapter's tests extend
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.assertj)
    testImplementation(libs.jackson.yaml)
    // The engine's own tests drive the shared host double and issuers (faye.engine.testing)
    testImplementation(testFixtures(project(":faye-engine")))
}

tasks.test {
    // FAYE_REGENERATE=1 rewrites conformance/generated instead of comparing against it
    inputs.property("regenerate", System.getenv("FAYE_REGENERATE") ?: "")
}

// Measured 2026-11-01: line 92.3%, branch 83.3%; floors round down to whole percent
tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.92".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.83".toBigDecimal()
            }
        }
    }
}
