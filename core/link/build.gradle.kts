plugins {
    alias(libs.plugins.ados.android.library)
    alias(libs.plugins.ados.android.hilt)
}

dependencies {
    api(projects.core.model)
    api(projects.core.mavlink)
    implementation(projects.core.data)
    implementation(projects.core.agent)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.usb.serial.android)
}
