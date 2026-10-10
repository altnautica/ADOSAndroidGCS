plugins {
    alias(libs.plugins.ados.android.library)
    alias(libs.plugins.ados.android.hilt)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.data)
    implementation(projects.core.video)
    implementation(libs.kotlinx.coroutines.android)
}
