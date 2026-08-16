import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.vmodal.smartglass"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        manifestPlaceholders["mwdat_application_id"] = "0"
        manifestPlaceholders["mwdat_client_token"] = "0"
        ndk.abiFilters += listOf("arm64-v8a", "x86_64")
    }

    flavorDimensions += "dat"
    productFlavors {
        create("offline") {
            dimension = "dat"
        }
        create("meta") {
            dimension = "dat"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.named("main") {
        jniLibs.srcDir(layout.buildDirectory.dir("native/jniLibs"))
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

val nativeOutput = layout.buildDirectory.dir("native/jniLibs")
val buildNativeRelease by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the existing Rust core for Android AAR ABIs"
    workingDir = rootProject.file("system_core")
    inputs.files(
        rootProject.file("system_core/Cargo.toml"),
        rootProject.file("system_core/Cargo.lock"),
        rootProject.fileTree("system_core/src") { include("**/*.rs") },
    )
    outputs.dir(nativeOutput)
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "-o", nativeOutput.get().asFile.absolutePath,
        "build", "--release",
    )
}

tasks.matching {
    it.name.startsWith("mergeMeta") && it.name.endsWith("JniLibFolders")
}.configureEach {
    dependsOn(buildNativeRelease)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    "metaImplementation"(libs.mwdat.core)
    "metaImplementation"(libs.mwdat.camera)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
    "androidTestMetaImplementation"(libs.mwdat.mockdevice)
}
