import com.altnautica.gcs.buildlogic.AdosSdk
import com.altnautica.gcs.buildlogic.addUnitTestDependencies
import com.altnautica.gcs.buildlogic.configureKotlinToolchain
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure

/** Pure Kotlin/JVM module with no Android dependency. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = AdosSdk.JAVA_VERSION
            targetCompatibility = AdosSdk.JAVA_VERSION
        }
        configureKotlinToolchain()
        addUnitTestDependencies()
    }
}
