pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
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
    "mcp-server",
    "auditor-cli",
    "visuals-core",
    "visuals-gradle-plugin",
    "visuals-android-test",
    "cli",
)
