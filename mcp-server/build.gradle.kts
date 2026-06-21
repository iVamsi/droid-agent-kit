plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":toolbox-core"))
    implementation(project(":android-inspector"))
    implementation(project(":auditor-cli"))
    testImplementation("junit:junit:4.13.2")
}
