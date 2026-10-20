plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.vmodal.smartglass.voicesearch.meta"
    compileSdk = 36
    flavorDimensions += "device"
    productFlavors {
        create("production") { dimension = "device" }
        create("mock") { dimension = "device" }
    }
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.speech)
    implementation(libs.mwdat.display)
    "mockImplementation"(libs.mwdat.mockdevice)
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(libs.mwdat.mockdevice)
}
