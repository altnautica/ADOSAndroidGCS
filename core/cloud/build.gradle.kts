plugins {
    alias(libs.plugins.ados.android.library)
    alias(libs.plugins.ados.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.data)
    implementation(projects.core.video)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hivemq.mqtt.client)
    // Published as a platform that adds the Netty WebSocket codec dependencies.
    implementation(platform(libs.hivemq.mqtt.client.websocket))
    implementation(libs.convex.android)
}
