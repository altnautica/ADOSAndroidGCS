plugins {
    alias(libs.plugins.ados.android.library)
    alias(libs.plugins.ados.android.compose)
    alias(libs.plugins.ados.android.hilt)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.designsystem)
    implementation(libs.kotlinx.coroutines.android)
    api(libs.maplibre.android)
}
