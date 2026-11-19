import com.altnautica.gcs.buildlogic.AdosSdk

plugins {
    alias(libs.plugins.ados.android.library)
    alias(libs.plugins.ados.android.compose)
    alias(libs.plugins.ados.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    ndkVersion = AdosSdk.NDK
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        consumerProguardFiles("consumer-rules.pro")
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// The bind tunnel's framing and lwIP endpoint are plain C/C++; their tests run
// on the build host (the SDK's CMake and Ninja, the host's C/C++ compiler)
// ahead of every unit-test run of this module.
val sdkCmakeBin = androidComponents.sdkComponents.sdkDirectory.map { it.dir("cmake/3.22.1/bin") }
val hostTestBuild = layout.buildDirectory.dir("host-native-test")

val configureHostNativeTest = tasks.register<Exec>("configureHostNativeTest") {
    inputs.files(fileTree("src/test/cpp"), fileTree("src/main/cpp") { include("bindtun.cmake") })
    outputs.file(hostTestBuild.map { it.file("build.ninja") })
    val bin = sdkCmakeBin.get()
    commandLine(
        bin.file("cmake").asFile.path, "-S", file("src/test/cpp").path, "-B", hostTestBuild.get().asFile.path,
        "-G", "Ninja", "-DCMAKE_MAKE_PROGRAM=${bin.file("ninja").asFile.path}", "-DCMAKE_BUILD_TYPE=Debug",
    )
}

val buildHostNativeTest = tasks.register<Exec>("buildHostNativeTest") {
    dependsOn(configureHostNativeTest)
    inputs.files(fileTree("src/test/cpp"), fileTree("src/main/cpp") { exclude("vendor/libsodium/**", "vendor/wfb-ng/**") })
    outputs.file(hostTestBuild.map { it.file("bind_tunnel_test") })
    commandLine(sdkCmakeBin.get().file("cmake").asFile.path, "--build", hostTestBuild.get().asFile.path)
}

val hostNativeTest = tasks.register<Exec>("hostNativeTest") {
    description = "Runs the bind tunnel's host-side native tests."
    dependsOn(buildHostNativeTest)
    val stamp = hostTestBuild.map { it.file("passed.stamp") }
    inputs.file(hostTestBuild.map { it.file("bind_tunnel_test") })
    outputs.file(stamp)
    commandLine(hostTestBuild.get().file("bind_tunnel_test").asFile.path)
    doLast { stamp.get().asFile.writeText("passed\n") }
}

tasks.withType<Test>().configureEach { dependsOn(hostNativeTest) }

dependencies {
    api(projects.core.model)
    implementation(projects.radio.usb)
    implementation(projects.core.link)
    implementation(projects.core.data)
    implementation(projects.core.agent)
    implementation(projects.core.video)
    implementation(projects.core.designsystem)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.androidx.datastore.preferences)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
}
