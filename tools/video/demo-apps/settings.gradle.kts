// Three tiny phone apps for recording the video series: they post the story's notifications under invented
// app names (Messages, Northwind Bank, Files). A separate Gradle project, so nothing here can reach the real app
// or its release build.   tools/video/phone-notifications.sh
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "MacNDroidDemoApps"
include(":app")
