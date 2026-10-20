plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "com.vmodal.smartglass.voicesearch"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.vmodal.smartglass.voicesearch"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["mwdat_application_id"] = providers.gradleProperty("metaApplicationId").orElse("").get()
        manifestPlaceholders["mwdat_client_token"] = providers.gradleProperty("metaClientToken").orElse("").get()
    }
    flavorDimensions += "device"
    productFlavors {
        create("production") { dimension = "device" }
        create("mock") { dimension = "device"; applicationIdSuffix = ".mock" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation(project(":adapters:meta"))
    implementation(project(":adapters:vmodal"))
    implementation(project(":adapters:image_android"))
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
