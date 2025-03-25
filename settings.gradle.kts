pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT) // ✅ Fix: Use a stable mode
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HateGuard"
include(":app") // ✅ Fix: Correct syntax
