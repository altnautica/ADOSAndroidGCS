plugins {
    alias(libs.plugins.ados.android.library)
    alias(libs.plugins.ados.android.compose)
    alias(libs.plugins.ados.android.hilt)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.data)
    implementation(projects.core.agent)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    api(libs.webrtc.android)
    testImplementation(libs.okhttp.mockwebserver)
}
