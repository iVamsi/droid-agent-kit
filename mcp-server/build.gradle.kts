plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":toolbox-core"))
    implementation(project(":android-inspector"))
    testImplementation("junit:junit:4.13.2")
}
