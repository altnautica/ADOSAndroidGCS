import java.util.Properties

plugins {
    alias(libs.plugins.ados.android.application)
    alias(libs.plugins.ados.android.compose)
    alias(libs.plugins.ados.android.hilt)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.kotlin.serialization)
}

// Build-time endpoints come from local.properties (gitignored) so that no
// deployment URL or token lands in the repository. Both are optional: an
// empty value builds an app with the matching feature switched off.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun localProperty(key: String): String =
    (localProperties.getProperty(key) ?: providers.gradleProperty(key).orNull ?: "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

android {
    namespace = "com.altnautica.gcs"

    defaultConfig {
        applicationId = "com.altnautica.gcs"
        versionCode = 8
        versionName = "0.2.0"

        buildConfigField("String", "CONVEX_URL", "\"${localProperty("ados.convexUrl")}\"")
        buildConfigField("String", "MAP_TOKEN", "\"${localProperty("ados.mapToken")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }

    lint {
        // The seven non-English locales cover a subset of the keys; Android
        // falls back to the default English string for the rest.
        warning += "MissingTranslation"
    }
}

baselineProfile {
    // The profile is generated on demand by :baselineprofile and committed under
    // src/main/generated/baselineProfiles so every build type ships it.
    automaticGenerationDuringBuild = false
    mergeIntoMain = true
}

/**
 * Copies the repository's THIRD_PARTY_NOTICES.md into generated assets so the
 * in-app licences screen always shows the committed file, never a hand copy.
 */
abstract class CopyThirdPartyNotices : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val notices: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        notices.get().asFile.copyTo(out.resolve("THIRD_PARTY_NOTICES.md"), overwrite = true)
    }
}

val copyThirdPartyNotices = tasks.register<CopyThirdPartyNotices>("copyThirdPartyNotices") {
    notices.set(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyThirdPartyNotices, CopyThirdPartyNotices::outputDir)
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.designsystem)
    implementation(projects.core.mavlink)
    implementation(projects.core.link)
    implementation(projects.core.data)
    implementation(projects.core.database)
    implementation(projects.core.agent)
    implementation(projects.core.video)
    implementation(projects.core.map)
    implementation(projects.core.skills)
    implementation(projects.core.extensions)
    implementation(projects.core.cloud)
    implementation(projects.radio.rx)
    implementation(projects.radio.usb)
    implementation(projects.feature.connect)
    implementation(projects.feature.fleet)
    implementation(projects.feature.node)
    implementation(projects.feature.settings)
    implementation(projects.feature.cockpit)
    implementation(projects.feature.plan)
    implementation(projects.feature.configure)
    implementation(projects.feature.logs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.service)

    baselineProfile(projects.baselineprofile)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
