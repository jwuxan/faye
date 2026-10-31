dependencies {
    api(project(":faye-engine"))
    implementation(libs.jackson.databind)
    // QuickJS compiled to WebAssembly, run on a pure Java Wasm runtime (ADR 0009)
    implementation(libs.quickjs4j)
    testImplementation(project(":faye-host-mock"))
}
