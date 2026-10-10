import com.altnautica.gcs.buildlogic.AdosSdk
import com.altnautica.gcs.buildlogic.addUnitTestDependencies
import com.altnautica.gcs.buildlogic.adosNamespace
import com.altnautica.gcs.buildlogic.configureKotlinToolchain
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            namespace = adosNamespace()
            compileSdk {
                version = release(AdosSdk.COMPILE) { minorApiLevel = AdosSdk.COMPILE_MINOR }
            }
            defaultConfig {
                minSdk = AdosSdk.MIN
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = AdosSdk.JAVA_VERSION
                targetCompatibility = AdosSdk.JAVA_VERSION
            }
            testOptions {
                targetSdk = AdosSdk.TARGET
                unitTests.isReturnDefaultValues = true
            }
            lint {
                targetSdk = AdosSdk.TARGET
            }
        }
        configureKotlinToolchain()
        addUnitTestDependencies()
    }
}
