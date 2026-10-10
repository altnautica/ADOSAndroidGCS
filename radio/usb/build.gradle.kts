// SPDX-License-Identifier: GPL-2.0-or-later
import com.altnautica.gcs.buildlogic.AdosSdk

plugins {
    alias(libs.plugins.ados.android.library)
}

android {
    ndkVersion = AdosSdk.NDK
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures { aidl = true }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
