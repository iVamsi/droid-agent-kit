package com.droidagentkit.visuals.gradle

import com.droidagentkit.core.ResultStatus
import com.droidagentkit.visuals.VisualCaptureEngine
import com.droidagentkit.visuals.VisualTolerance
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

class DroidAgentVisualsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("droidAgentVisuals", DroidAgentVisualsExtension::class.java)
        project.tasks.register("droidAgentVisualsReport", DroidAgentVisualsReportTask::class.java) { task ->
            task.group = "verification"
            task.description = "Writes a DroidAgentKit visual report comparing captures against goldens."
            task.outputDir.set(extension.outputDir)
            task.goldensDir.set(extension.goldensDir)
            task.packageName.set(extension.packageName)
            task.maxChangedPixelPercent.set(extension.tolerance.maxChangedPixelPercent)
            task.maxColorDistance.set(extension.tolerance.maxColorDistance)
            task.failOnChangedGoldens.set(extension.failOnChangedGoldens)
        }
        project.tasks.register("droidAgentVisualsUpdateGoldens", DroidAgentVisualsUpdateGoldensTask::class.java) { task ->
            task.group = "verification"
            task.description = "Updates DroidAgentKit visual golden images explicitly."
            task.outputDir.set(extension.outputDir)
            task.goldensDir.set(extension.goldensDir)
        }
    }
}

abstract class DroidAgentVisualsExtension
    @Inject
    constructor(
        project: Project,
    ) {
        val outputDir: DirectoryProperty =
            project.objects
                .directoryProperty()
                .convention(project.layout.buildDirectory.dir("droidagentkit/visuals"))
        val goldensDir: DirectoryProperty =
            project.objects
                .directoryProperty()
                .convention(project.layout.projectDirectory.dir("src/test/resources/droidagentkit/goldens"))
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

abstract class VisualMatrixSpec
    @Inject
    constructor(
        project: Project,
    ) {
        val devices: ListProperty<String> = project.objects.listProperty(String::class.java).convention(listOf("phone_412x915"))
        val themes: ListProperty<String> = project.objects.listProperty(String::class.java).convention(listOf("light", "dark"))
        val fontScales: ListProperty<Float> = project.objects.listProperty(Float::class.java).convention(listOf(1.0f, 1.3f, 2.0f))
        val locales: ListProperty<String> = project.objects.listProperty(String::class.java).convention(listOf("en"))
    }

abstract class VisualToleranceSpec
    @Inject
    constructor(
        project: Project,
    ) {
        val maxChangedPixelPercent: Property<Double> = project.objects.property(Double::class.java).convention(0.10)
        val maxColorDistance: Property<Int> = project.objects.property(Int::class.java).convention(3)
    }

abstract class DroidAgentVisualsReportTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Optional
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val goldensDir: DirectoryProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val maxChangedPixelPercent: Property<Double>

    @get:Input
    abstract val maxColorDistance: Property<Int>

    @get:Input
    abstract val failOnChangedGoldens: Property<Boolean>

    @TaskAction
    fun writeReport() {
        val tolerance = VisualTolerance(maxChangedPixelPercent.get(), maxColorDistance.get())
        val report =
            VisualCaptureEngine.generateReport(
                outputDir.get().asFile.toPath(),
                goldensDir.get().asFile.toPath(),
                tolerance,
            )
        val file = outputDir.file("visual-report.md").get().asFile
        file.parentFile.mkdirs()
        file.writeText(VisualCaptureEngine.renderMarkdown(report, packageName.orNull ?: "unknown"))
        if (failOnChangedGoldens.get() && report.status == ResultStatus.FAILED) {
            throw GradleException("DroidAgentKit visual regression detected: ${report.findings.size} finding(s). See $file")
        }
    }
}

abstract class DroidAgentVisualsUpdateGoldensTask : DefaultTask() {
    @get:Internal
    abstract val outputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val goldensDir: DirectoryProperty

    @TaskAction
    fun updateGoldens() {
        val updated =
            VisualCaptureEngine.updateGoldens(
                outputDir.get().asFile.toPath(),
                goldensDir.get().asFile.toPath(),
            )
        logger.lifecycle("DroidAgentKit updated ${updated.size} golden image(s) in ${goldensDir.get().asFile}")
    }
}
