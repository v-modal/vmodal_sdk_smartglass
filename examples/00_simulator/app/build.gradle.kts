import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.vmodal.smartglass.simulator"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vmodal.smartglass.simulator"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        manifestPlaceholders["mwdat_application_id"] = ""
        manifestPlaceholders["mwdat_client_token"] = ""
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

dependencies {
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.mockdevice)
}
