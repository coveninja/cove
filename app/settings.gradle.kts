pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        ivy {
            name = "jlibtorrentGitHubRelease"
            url = uri("https://github.com/frostwire/frostwire-jlibtorrent/releases/download/release/2.0.12.9")
            patternLayout { artifact("[artifact]-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeGroup("com.frostwire") }
        }
    }
}

rootProject.name = "cove"
include(":shared", ":backend", ":ui", ":desktop", ":mobile", ":benchmark")
