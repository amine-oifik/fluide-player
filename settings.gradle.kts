pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "FluidePlayer"

// :core  -> pure Kotlin (no Android): parsers + anti-freeze logic, unit-tested on the JVM.
// :app   -> Android TV / mobile app: Media3, SQLite, Compose.
include(":core", ":app")
