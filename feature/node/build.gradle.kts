plugins {
    alias(libs.plugins.ados.android.library)
    alias(libs.plugins.ados.android.compose)
    alias(libs.plugins.ados.android.hilt)
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.designsystem)
    implementation(projects.core.agent)
    implementation(projects.core.link)
    implementation(projects.core.video)
    implementation(projects.core.extensions)
    implementation(projects.radio.rx)
    implementation(projects.feature.fleet)
    implementation(projects.feature.configure)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.serialization.json)
}
