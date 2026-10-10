plugins {
    alias(libs.plugins.ados.jvm.library)
    alias(libs.plugins.ados.mavlink.codegen)
}

dependencies {
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.serialization.json)
}
