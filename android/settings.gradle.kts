// settings.gradle.kts — Gradle settings for the Cove Android project.
// The AAR produced by `make android-aar` lives at app/libs/cove.aar and is
// referenced as a file dependency in app/build.gradle.kts; it does not need
// its own Gradle subproject.

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

rootProject.name = "cove"
include(":app")
