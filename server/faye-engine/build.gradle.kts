dependencies {
    api(project(":faye-ir"))
    api(libs.jackson.annotations)
    implementation(libs.jackson.databind)
    // Verifier checks call args against each handler's args schema, as IrCodec checks the IR (ADR 0024)
    implementation(libs.json.schema.validator)
    testImplementation(project(":faye-host-mock"))
    testImplementation(libs.jackson.yaml)
}
