package com.droidagentkit.visuals.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DroidAgentVisualsPluginTest {
    @Test
    fun `plugin registers expected visual tasks and extension`() {
        val project = ProjectBuilder.builder().build()

        project.plugins.apply(DroidAgentVisualsPlugin::class.java)

        assertNotNull(project.extensions.findByName("droidAgentVisuals"))
        assertNotNull(project.tasks.findByName("droidAgentVisualsReport"))
        assertNotNull(project.tasks.findByName("droidAgentVisualsUpdateGoldens"))
        assertTrue(project.tasks.getByName("droidAgentVisualsUpdateGoldens").description!!.contains("golden"))
    }
}
