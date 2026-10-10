plugins {
    alias(libs.plugins.ados.android.library)
    alias(libs.plugins.ados.android.compose)
    alias(libs.plugins.ados.android.hilt)
}

android {
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.designsystem)
    implementation(projects.core.skills)
    implementation(projects.core.video)
    implementation(projects.core.map)
    implementation(projects.core.extensions)
    implementation(projects.core.data)
    implementation(projects.core.agent)
    implementation(projects.core.link)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.navigationevent.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
