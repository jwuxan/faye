plugins {
    `java-test-fixtures`
}

dependencies {
    api(project(":faye-engine"))
    testFixturesApi(project(":faye-engine"))
    testFixturesImplementation(libs.jspecify)
    testImplementation(libs.jackson.databind)
}
