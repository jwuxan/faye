dependencies {
    api(project(":faye-engine"))
    testImplementation(libs.jackson.databind)
    // Ory's official admin client (okhttp + Gson), matching the pinned Hydra 2.2.0 (ADR 0021). The
    // OAuth2 pieces are excluded: the admin API is called without OAuth.
    implementation(libs.hydra.client) {
        exclude(group = "org.apache.oltu.oauth2", module = "org.apache.oltu.oauth2.client")
        exclude(group = "javax.ws.rs", module = "jsr311-api")
        exclude(group = "javax.ws.rs", module = "javax.ws.rs-api")
    }
    compileOnly(libs.jakarta.annotation.api)
}
