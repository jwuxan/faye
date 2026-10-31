dependencies {
    api(project(":faye-ir"))
    implementation(libs.snakeyaml)
}

tasks.test {
    // FAYE_REGENERATE=1 rewrites the lowering goldens under src/test/resources/golden
    inputs.property("regenerate", System.getenv("FAYE_REGENERATE") ?: "")
}
