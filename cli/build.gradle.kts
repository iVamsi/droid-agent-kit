plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":toolbox-core"))
    implementation(project(":android-inspector"))
    implementation(project(":mcp-server"))
    implementation(project(":auditor-cli"))
    implementation(project(":visuals-core"))
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.droidagentkit.cli.DroidAgentMainKt")
    applicationName = "droidagent"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
