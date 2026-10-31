dependencies {
    api(libs.jackson.databind)
    // readFlow validates against schema/ir.schema.json (ADR 0001); faye-app tests still use it for
    // the screen protocol
    implementation(libs.json.schema.validator)
}

tasks.processResources {
    // The IR grammar lives at the repo root, outside this build; ship it on the classpath
    from(rootDir.parentFile.resolve("schema")) {
        include("ir.schema.json")
    }
}
