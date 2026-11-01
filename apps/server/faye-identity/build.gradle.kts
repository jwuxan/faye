plugins {
    id("faye.java-conventions")
}

dependencies {
    api("faye:faye-engine")
    // The blocks of these handlers implement the YAML surface's BlockSpec (ADR 0030)
    implementation("faye:faye-core")
    implementation(libs.jackson.databind)
    // Argon2PasswordEncoder; Argon2 itself comes from Bouncy Castle
    implementation(appLibs.spring.security.crypto)
    runtimeOnly(appLibs.spring.jcl)
    implementation(appLibs.bcprov)
    implementation(appLibs.webauthn4j.core)
    // webauthn4j signatures carry these annotations; javac needs them to read the classes without warnings
    compileOnly(appLibs.jetbrains.annotations)
    testCompileOnly(appLibs.jetbrains.annotations)
    testImplementation(appLibs.webauthn4j.test)
    testImplementation(testFixtures("faye:faye-engine"))
}
