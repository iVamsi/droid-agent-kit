plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.20"
}

dependencies {
    implementation(project(":toolbox-core"))
    // HAR (JSON) parsing for mitmproxy flow dumps. kotlinx-serialization-json is the project's one
    // allowed third-party runtime dependency (already used by mcp-server/cli). Scoped to this module.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation("junit:junit:4.13.2")
}
