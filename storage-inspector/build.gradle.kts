plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":toolbox-core"))
    // SQLite JDBC is scoped to this module only (Tranche 7 dependency gate). Apache-2.0; bundles
    // native SQLite for linux/mac/windows. Used in read-only mode for app-data snapshot inspection.
    implementation("org.xerial:sqlite-jdbc:3.53.2.0")
    testImplementation("junit:junit:4.13.2")
}
