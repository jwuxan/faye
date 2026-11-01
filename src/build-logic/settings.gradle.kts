dependencyResolutionManagement {
    versionCatalogs {
        // The convention plugin resolves the same versions the product does
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
