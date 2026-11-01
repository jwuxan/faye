plugins {
    id("faye.java-conventions")
    alias(appLibs.plugins.spring.boot)
}

dependencies {
    implementation("faye:faye-core")
    implementation("faye:faye-engine")
    implementation(project(":faye-clients"))
    implementation(project(":faye-identity"))
    implementation(project(":faye-host-hydra"))
    // The in memory host (a test fixture of the engine) serves challenges when no Hydra admin URL
    // is configured (local dev, tests)
    implementation(testFixtures("faye:faye-engine"))
    implementation(appLibs.spring.boot.starter.web)
    implementation(appLibs.spring.boot.starter.jdbc)
    // Flyway runs the versioned migrations under db/migration on this datasource (ADR 0020)
    implementation(appLibs.flyway.core)
    // Every store, in one database file shared with the authorization server (ADR 0017)
    runtimeOnly(appLibs.sqlite.jdbc)
    testImplementation(appLibs.spring.boot.starter.test)
    testImplementation(libs.json.schema.validator)
}
