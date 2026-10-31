plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":faye-dsl"))
    implementation(project(":faye-check"))
    implementation(project(":faye-engine"))
    implementation(project(":faye-hooks"))
    implementation(project(":faye-identity"))
    implementation(project(":faye-host-hydra"))
    // Serves challenges when no Hydra admin URL is configured (local dev, tests)
    implementation(project(":faye-host-mock"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.redis)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.json.schema.validator)
}
