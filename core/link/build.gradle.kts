plugins {
    alias(libs.plugins.ados.android.library)
    alias(libs.plugins.ados.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    api(projects.core.mavlink)
    implementation(projects.core.data)
    implementation(projects.core.agent)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.usb.serial.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.androidx.datastore.preferences)
}
