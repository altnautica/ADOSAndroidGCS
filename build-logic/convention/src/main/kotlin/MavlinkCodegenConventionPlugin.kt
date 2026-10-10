import com.altnautica.gcs.buildlogic.AdosSdk
import com.altnautica.gcs.buildlogic.mavlink.GenerateMavlinkTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Generates MAVLink codecs from `definitions/ardupilotmega.xml` (and everything
 * it includes) into `build/generated/mavlink/` and adds them to the main
 * Kotlin source set. Applied after `ados.jvm.library`.
 */
class MavlinkCodegenConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val generate = tasks.register<GenerateMavlinkTask>("generateMavlink") {
            group = "build"
            description = "Generates Kotlin MAVLink codecs from the XML definitions."
            definitionsDir.set(layout.projectDirectory.dir("definitions"))
            dialect.set("ardupilotmega.xml")
            basePackage.set(AdosSdk.BASE_NAMESPACE + ".core.mavlink")
            outputDir.set(layout.buildDirectory.dir("generated/mavlink"))
        }
        pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            extensions.configure<KotlinJvmProjectExtension> {
                sourceSets.getByName("main").kotlin.srcDir(generate.flatMap { it.outputDir })
            }
        }
    }
}
