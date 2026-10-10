package com.altnautica.gcs.buildlogic.mavlink

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Generates Kotlin codecs from a MAVLink dialect XML and its includes. */

abstract class GenerateMavlinkTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val definitionsDir: DirectoryProperty

    /** Dialect file name inside [definitionsDir], e.g. `ardupilotmega.xml`. */
    @get:Input
    abstract val dialect: Property<String>

    @get:Input
    abstract val basePackage: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val dialectFile = definitionsDir.file(dialect).get().asFile
        val parsed = MavlinkDefinitionParser.parse(dialectFile)
        MavlinkKotlinGenerator(basePackage.get()).generate(parsed, outputDir.get().asFile)
        logger.info("Generated ${parsed.messages.size} MAVLink messages and ${parsed.enums.size} enums")
    }
}
