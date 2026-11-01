plugins {
    id("faye.library-conventions")
}

description = "The Faye IR, YAML DSL and hook runtime."

dependencies {
    api(libs.jackson.databind)
    implementation(libs.snakeyaml)
    // readFlow validates against schema/ir.schema.json
    implementation(libs.json.schema.validator)
    implementation(libs.slf4j.api)
    // QuickJS compiled to WebAssembly, run on a pure Java Wasm runtime
    implementation(libs.quickjs4j)
}

tasks.processResources {
    // The IR grammar lives beside the build at src/schema; ship it on the classpath
    from(rootDir.resolve("schema")) {
        include("ir.schema.json")
    }
}

tasks.test {
    // FAYE_REGENERATE=1 rewrites the lowering goldens under src/test/resources/golden
    inputs.property("regenerate", System.getenv("FAYE_REGENERATE") ?: "")
}

// Measured 2026-11-01: line 87.7%, branch 79.3%; floors round down to whole percent
tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.87".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.79".toBigDecimal()
            }
        }
    }
}
