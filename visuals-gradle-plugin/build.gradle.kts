plugins {
    kotlin("jvm")
    `java-gradle-plugin`
}

dependencies {
    implementation(project(":visuals-core"))
    testImplementation("junit:junit:4.13.2")
}

gradlePlugin {
    plugins {
        create("composeVisuals") {
            id = "com.droidagentkit.compose-visuals"
            implementationClass = "com.droidagentkit.visuals.gradle.DroidAgentVisualsPlugin"
        }
    }
}
