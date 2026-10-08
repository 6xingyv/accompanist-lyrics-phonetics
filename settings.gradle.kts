pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "lyrics-phonetics"
include(":runtime", ":data-compiler", ":data-mandarin", ":data-cantonese", ":data-japanese", ":benchmark")
include(":device-benchmark")
