import com.altnautica.gcs.buildlogic.AdosSdk
import com.altnautica.gcs.buildlogic.addUnitTestDependencies
import com.altnautica.gcs.buildlogic.configureKotlinToolchain
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")

        extensions.configure<ApplicationExtension> {
            compileSdk {
                version = release(AdosSdk.COMPILE) { minorApiLevel = AdosSdk.COMPILE_MINOR }
            }
            defaultConfig {
                minSdk = AdosSdk.MIN
                targetSdk = AdosSdk.TARGET
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = AdosSdk.JAVA_VERSION
                targetCompatibility = AdosSdk.JAVA_VERSION
            }
            buildFeatures {
                buildConfig = true
            }
            testOptions {
                unitTests.isReturnDefaultValues = true
            }
        }
        configureKotlinToolchain()
        addUnitTestDependencies()
    }
}
