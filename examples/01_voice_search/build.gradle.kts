import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    id("com.android.application") version "8.11.1" apply false
    id("com.android.library") version "8.11.1" apply false
    kotlin("android") version "2.2.21" apply false
    kotlin("jvm") version "2.2.21" apply false
}

tasks.register("verifyBoundaries") {
    group = "verification"
    doLast {
        val coreDeps = project(":core").configurations.getByName("runtimeClasspath").incoming.resolutionResult.allComponents.mapNotNull { it.id as? ModuleComponentIdentifier }
        check(coreDeps.none { it.group.startsWith("com.meta") || it.group == "com.vmodal" || it.group.startsWith("androidx") })
        val production = project(":app").configurations.getByName("productionReleaseRuntimeClasspath").incoming.resolutionResult.allComponents.mapNotNull { it.id as? ModuleComponentIdentifier }
        check(production.none { it.module == "mwdat-mockdevice" }) { "MockDeviceKit leaked into production" }
        check(production.any { it.group == "com.vmodal" && it.module == "vmodal-sdk-android" }) { "The standalone Maven SDK is required" }
        listOf(":adapters:meta" to "productionReleaseRuntimeClasspath", ":adapters:image_android" to "releaseRuntimeClasspath", ":adapters:vmodal" to "runtimeClasspath").forEach { (module, config) ->
            val components = project(module).configurations.getByName(config).incoming.resolutionResult.allComponents.mapNotNull { it.id as? ModuleComponentIdentifier }
            if (module != ":adapters:meta") check(components.none { it.group.startsWith("com.meta") }) { "DAT dependency outside Meta adapter: $module" }
            if (module != ":adapters:vmodal") check(components.none { it.group == "com.vmodal" }) { "SDK dependency outside VModal adapter: $module" }
        }
        listOf("core", "app", "adapters/meta", "adapters/vmodal", "adapters/image_android").forEach { module ->
            fileTree("$module/src").matching { include("**/*.kt") }.forEach { source ->
                if (module != "adapters/meta") check(!source.readText().contains("import com.meta.wearable")) { "Meta import outside adapter: $source" }
                if (module != "adapters/vmodal") check(!source.readText().contains("import com.vmodal.sdk")) { "SDK import outside adapter: $source" }
            }
        }
        println("Verified core/vendor isolation, Maven SDK consumption, and production excludes MockDeviceKit")
    }
}
