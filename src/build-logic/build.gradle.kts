plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation("net.ltgt.errorprone:net.ltgt.errorprone.gradle.plugin:${libs.versions.errorprone.plugin.get()}")
    implementation("com.diffplug.spotless:com.diffplug.spotless.gradle.plugin:${libs.versions.spotless.plugin.get()}")
}
