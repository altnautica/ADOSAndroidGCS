plugins {
    alias(libs.plugins.ados.android.library)
    alias(libs.plugins.ados.android.compose)
    alias(libs.plugins.ados.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.agent)
    implementation(projects.core.link)
    implementation(projects.core.skills)
    implementation(projects.core.designsystem)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.okhttp.mockwebserver)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// The relay page script is plain JavaScript; its behaviour tests run on Node's built-in
// test runner as part of the unit-test task.
val relayJsTest by tasks.registering(Exec::class) {
    description = "Runs the extension relay script tests with node --test."
    group = "verification"
    val testFile = layout.projectDirectory.file("src/test/js/relay.test.js")
    inputs.file(testFile)
    inputs.dir(layout.projectDirectory.dir("src/main/assets/extension-host"))
    val stamp = layout.buildDirectory.file("test-results/relayJs/passed.txt")
    outputs.file(stamp)
    commandLine("node", "--test", testFile.asFile.absolutePath)
    doLast { stamp.get().asFile.apply { parentFile.mkdirs(); writeText("ok") } }
}

tasks.matching { it.name == "testDebugUnitTest" || it.name == "testReleaseUnitTest" }.configureEach {
    dependsOn(relayJsTest)
}
