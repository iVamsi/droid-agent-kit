plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.4.10"
}

dependencies {
    implementation(project(":toolbox-core"))
    implementation(project(":android-inspector"))
    implementation(project(":android-device-core"))
    implementation(project(":auditor-cli"))
    implementation(project(":perfetto-core"))
    implementation(project(":visuals-core"))
    implementation(project(":storage-inspector"))
    implementation(project(":network-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation("junit:junit:4.13.2")
}
