plugins {
    alias(libs.plugins.ados.android.library)
    alias(libs.plugins.ados.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.data)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // The demo autopilot behind the real MAVLink link drives the skill pipeline end to end.
    testImplementation(projects.core.link)
}
