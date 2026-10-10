plugins {
    alias(libs.plugins.ados.android.library)
    alias(libs.plugins.ados.android.compose)
    alias(libs.plugins.ados.android.hilt)
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.designsystem)
    implementation(projects.core.data)
    implementation(projects.core.link)
    implementation(projects.feature.cockpit)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
}
