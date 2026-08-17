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
        maven("https://jitpack.io")
    }
}

rootProject.name = "UCloneRestore"
include(":app")
include(":launcher-module")
include(":crossuser-api")
include(":crossuser-runtime")
include(":crossuser-probe-app")
