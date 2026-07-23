pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version "2.4.0"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "droid-agent-kit"

include(
    "toolbox-core",
    "android-inspector",
    "android-device-core",
    "mcp-server",
    "auditor-cli",
    "visuals-core",
    "visuals-gradle-plugin",
    "visuals-android-test",
    "perfetto-core",
    "storage-inspector",
    "network-core",
    "cli",
)
