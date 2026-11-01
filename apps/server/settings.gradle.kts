pluginManagement {
    includeBuild("../../src/build-logic")
}

dependencyResolutionManagement {
    versionCatalogs {
        // libs is the product's catalog, so the convention plugin and shared libraries resolve
        // identical versions; appLibs is this application's own
        create("libs") {
            from(files("../../src/gradle/libs.versions.toml"))
        }
        create("appLibs") {
            from(files("gradle/app-libs.versions.toml"))
        }
    }
}

// The product: faye-core and faye-engine resolve as faye:faye-core / faye:faye-engine
includeBuild("../../src")

rootProject.name = "faye-server"

include(
    "faye-identity",
    "faye-clients",
    "faye-host-hydra",
    "faye-app",
)
