dependencies {
    api(project(":faye-engine"))
    testImplementation(project(":faye-dsl"))
    testImplementation(testFixtures(project(":faye-screens")))
    testImplementation(project(":faye-host-mock"))
    testImplementation(libs.jackson.yaml)
    // Screens the engine fuzz test produces must match the screen protocol (ADR 0007, O14)
    testImplementation(libs.json.schema.validator)
}

tasks.test {
    // FAYE_REGENERATE=1 rewrites conformance/generated instead of comparing against it
    inputs.property("regenerate", System.getenv("FAYE_REGENERATE") ?: "")
}
