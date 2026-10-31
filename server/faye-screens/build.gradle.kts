plugins {
    `java-test-fixtures`
}

dependencies {
    api(project(":faye-engine"))
    testFixturesApi(project(":faye-engine"))
    // Versions come from the Spring Boot BOM, which the root build adds to implementation only
    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesImplementation(libs.jspecify)
    testImplementation(libs.jackson.databind)
}
