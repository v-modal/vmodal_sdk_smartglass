plugins {
    kotlin("jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation("com.vmodal:vmodal-sdk-android:${providers.gradleProperty("vmodalSdkVersion").getOrElse("2.0.3")}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
