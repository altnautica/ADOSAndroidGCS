plugins {
    alias(libs.plugins.ados.android.library)
    alias(libs.plugins.ados.android.hilt)
}

dependencies {
    api(projects.core.model)
    api(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
}
