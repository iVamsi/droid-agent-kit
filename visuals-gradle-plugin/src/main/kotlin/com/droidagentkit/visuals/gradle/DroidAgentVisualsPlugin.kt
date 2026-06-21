package com.droidagentkit.visuals.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import javax.inject.Inject

class DroidAgentVisualsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("droidAgentVisuals", DroidAgentVisualsExtension::class.java)
        project.tasks.register("droidAgentVisualsReport", DroidAgentVisualsReportTask::class.java) { task ->
            task.group = "verification"
            task.description = "Writes a DroidAgentKit visual report placeholder for collected visual artifacts."
            task.outputDir.set(extension.outputDir)
            task.packageName.set(extension.packageName)
        }
        project.tasks.register("droidAgentVisualsUpdateGoldens", DroidAgentVisualsUpdateGoldensTask::class.java) { task ->
            task.group = "verification"
            task.description = "Updates DroidAgentKit visual golden images explicitly."
            task.outputDir.set(extension.outputDir)
        }
    }
}

abstract class DroidAgentVisualsExtension @Inject constructor(project: Project) {
    val outputDir: DirectoryProperty = project.objects.directoryProperty()
        .convention(project.layout.buildDirectory.dir("droidagentkit/visuals"))
    val packageName: Property<String> = project.objects.property(String::class.java).convention("")
    val failOnChangedGoldens: Property<Boolean> = project.objects.property(Boolean::class.java).convention(true)
    val failOnAccessibilityWarnings: Property<Boolean> = project.objects.property(Boolean::class.java).convention(false)
    val matrix: VisualMatrixSpec = project.objects.newInstance(VisualMatrixSpec::class.java)
    val tolerance: VisualToleranceSpec = project.objects.newInstance(VisualToleranceSpec::class.java)

    fun matrix(action: VisualMatrixSpec.() -> Unit) {
        matrix.action()
    }

    fun tolerance(action: VisualToleranceSpec.() -> Unit) {
        tolerance.action()
    }
}

abstract class VisualMatrixSpec @Inject constructor(project: Project) {
    val devices: ListProperty<String> = project.objects.listProperty(String::class.java).convention(listOf("phone_412x915"))
    val themes: ListProperty<String> = project.objects.listProperty(String::class.java).convention(listOf("light", "dark"))
    val fontScales: ListProperty<Float> = project.objects.listProperty(Float::class.java).convention(listOf(1.0f, 1.3f, 2.0f))
    val locales: ListProperty<String> = project.objects.listProperty(String::class.java).convention(listOf("en"))
}

abstract class VisualToleranceSpec @Inject constructor(project: Project) {
    val maxChangedPixelPercent: Property<Double> = project.objects.property(Double::class.java).convention(0.10)
    val maxColorDistance: Property<Int> = project.objects.property(Int::class.java).convention(3)
}

abstract class DroidAgentVisualsReportTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val packageName: Property<String>

    @TaskAction
    fun writeReport() {
        val file = outputDir.file("visual-report.md").get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            # DroidAgentKit Visual Report

            Package: ${packageName.orNull ?: "unknown"}

            No visual cases were collected by this alpha task. Add DroidAgentVisualRule-based tests to produce case artifacts.
            """.trimIndent(),
        )
    }
}

abstract class DroidAgentVisualsUpdateGoldensTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun writeUpdateMarker() {
        val file = outputDir.file("goldens-updated.txt").get().asFile
        file.parentFile.mkdirs()
        file.writeText("Golden update requested explicitly.\n")
    }
}
