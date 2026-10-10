pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.mik3y") }
        }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "ADOSAndroidGCS"

include(":app")
include(":core:model")
include(":core:designsystem")
include(":core:mavlink")
include(":core:link")
include(":core:data")
include(":core:database")
include(":core:agent")
include(":core:video")
include(":core:map")
include(":core:skills")
include(":core:extensions")
include(":core:cloud")
include(":radio:rx")
include(":radio:usb")
include(":feature:connect")
include(":feature:fleet")
include(":feature:node")
include(":feature:settings")
include(":feature:cockpit")
include(":feature:plan")
include(":feature:configure")
include(":feature:logs")
include(":baselineprofile")
