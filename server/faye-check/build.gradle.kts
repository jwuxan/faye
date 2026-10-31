dependencies {
    api(project(":faye-engine"))
    testImplementation(project(":faye-dsl"))
    testImplementation(testFixtures(project(":faye-screens")))
    testImplementation(project(":faye-host-mock"))
    testImplementation(libs.jackson.yaml)
}

tasks.test {
    // FAYE_REGENERATE=1 rewrites conformance/generated instead of comparing against it
    inputs.property("regenerate", System.getenv("FAYE_REGENERATE") ?: "")
}
