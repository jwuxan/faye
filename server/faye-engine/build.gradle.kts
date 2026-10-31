dependencies {
    api(project(":faye-ir"))
    api(libs.jackson.annotations)
    implementation(libs.jackson.databind)
    testImplementation(project(":faye-host-mock"))
    testImplementation(libs.jackson.yaml)
}
