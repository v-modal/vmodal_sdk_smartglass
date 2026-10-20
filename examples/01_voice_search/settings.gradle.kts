pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val sdkRepo = providers.gradleProperty("vmodalMavenRepo").orNull
        if (sdkRepo != null) {
            exclusiveContent {
                forRepository { maven { url = uri(sdkRepo) } }
                filter { includeModule("com.vmodal", "vmodal-sdk-android") }
            }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "vmodal-glasses-voice-search"
include(":core", ":adapters:meta", ":adapters:vmodal", ":adapters:image_android", ":app")
