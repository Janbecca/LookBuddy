pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "LookBuddyProductIntegration"
include(":app")
project(":app").projectDir = file("../app")
project(":app").buildFileName = "integration.gradle.kts"
