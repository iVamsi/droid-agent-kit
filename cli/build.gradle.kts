plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    alias(libs.plugins.shadow)
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

// Release pipeline builds this via `./gradlew :cli:shadowJar` and publishes the jar as a
// GitHub Release asset; the npm launcher downloads it and runs it with a plain `java -jar`.
tasks.shadowJar {
    archiveBaseName.set("droidagent-cli")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    mergeServiceFiles()
}
