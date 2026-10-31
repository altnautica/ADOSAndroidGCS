plugins {
    alias(libs.plugins.ados.android.library)
    alias(libs.plugins.ados.android.hilt)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.link)
    implementation(projects.core.video)
    implementation(projects.core.agent)
    implementation(projects.core.data)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}
