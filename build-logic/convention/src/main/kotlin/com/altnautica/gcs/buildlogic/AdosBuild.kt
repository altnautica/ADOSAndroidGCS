package com.altnautica.gcs.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension

/** SDK and toolchain levels shared by every module. */
object AdosSdk {
    const val COMPILE = 37

    /** Minor API level of the compile platform (`platforms;android-37.0`). */
    const val COMPILE_MINOR = 0
    const val MIN = 29
    const val TARGET = 36
    const val JVM = 17
    val JAVA_VERSION: JavaVersion = JavaVersion.VERSION_17

    /** NDK used by modules that build native code. */
    const val NDK = "26.3.11579264"

    const val BASE_NAMESPACE = "com.altnautica.gcs"
}

val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** `:core:link` becomes `com.altnautica.gcs.core.link`. */
fun Project.adosNamespace(): String = AdosSdk.BASE_NAMESPACE + path.replace(':', '.')

fun Project.configureKotlinToolchain() {
    extensions.findByType(KotlinBaseExtension::class.java)?.jvmToolchain(AdosSdk.JVM)
}

fun Project.addUnitTestDependencies() {
    dependencies {
        add("testImplementation", libs.findLibrary("junit").get())
        add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
        add("testImplementation", libs.findLibrary("turbine").get())
        add("testImplementation", libs.findLibrary("mockk").get())
    }
}
