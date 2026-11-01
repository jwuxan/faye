dependencies {
    api(project(":faye-engine"))
    // The blocks of these handlers implement the YAML surface's BlockSpec (ADR 0030)
    implementation(project(":faye-dsl"))
    implementation(libs.jackson.databind)
    // Argon2PasswordEncoder; Argon2 itself comes from Bouncy Castle
    implementation(libs.spring.security.crypto)
    // the commons-logging API spring-security-crypto logs through
    runtimeOnly(libs.spring.jcl)
    implementation(libs.bcprov)
    implementation(libs.webauthn4j.core)
    // webauthn4j signatures carry these annotations; javac needs them to read the classes without warnings
    compileOnly(libs.jetbrains.annotations)
    testCompileOnly(libs.jetbrains.annotations)
    testImplementation(libs.webauthn4j.test)
    testImplementation(project(":faye-host-mock"))
    testImplementation(project(":faye-screens"))
}
