plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.20"
    application
}

dependencies {
    implementation(project(":toolbox-core"))
    implementation(project(":android-inspector"))
    implementation(project(":android-device-core"))
    implementation(project(":mcp-server"))
    implementation(project(":auditor-cli"))
    implementation(project(":visuals-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.droidagentkit.cli.DroidAgentMainKt")
    applicationName = "droidagent"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
