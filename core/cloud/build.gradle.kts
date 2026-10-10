plugins {
    alias(libs.plugins.ados.android.library)
    alias(libs.plugins.ados.android.compose)
    alias(libs.plugins.ados.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.data)
    implementation(projects.core.agent)
    implementation(projects.core.link)
    implementation(projects.core.video)
    implementation(projects.core.designsystem)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.hivemq.mqtt.client)
    // Published as a platform that adds the Netty WebSocket codec dependencies.
    implementation(platform(libs.hivemq.mqtt.client.websocket))
    implementation(libs.convex.android)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    testImplementation(libs.okhttp.mockwebserver)
}
