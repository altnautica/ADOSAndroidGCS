plugins {
    alias(libs.plugins.ados.android.library)
    alias(libs.plugins.ados.android.hilt)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.mavlink)
    implementation(projects.core.database)
    implementation(libs.kotlinx.coroutines.android)
}
