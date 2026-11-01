import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    id("faye.java-conventions")
    `maven-publish`
    jacoco
}

// The shippable libraries get stricter gates than the prototype: all of Error Prone's default
// checks on top of NullAway, published sources and javadoc jars, reproducible archives and
// coverage floors. A check disabled here must carry its reason on the same line.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        disableAllChecks = false
        check("NullAway", CheckSeverity.ERROR)
    }
}

extensions.configure<JavaPluginExtension> {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:all,-missing", "-quiet")
        addBooleanOption("Xwerror", true)
        encoding = "UTF-8"
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

extensions.configure<PublishingExtension>("publishing") {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            pom {
                name.set("Faye ${project.name}")
                description.set((project.description ?: "").ifBlank { "Faye ${project.name}" })
            }
        }
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

// Each module sets its LINE and BRANCH floors (measured, rounded down to whole percent)
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
}

tasks.check {
    dependsOn(tasks.jacocoTestReport)
    dependsOn(tasks.jacocoTestCoverageVerification)
}
