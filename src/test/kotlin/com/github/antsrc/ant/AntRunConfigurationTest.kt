package com.github.antsrc.ant

import com.github.antsrc.ant.run.AntRunConfiguration
import com.github.antsrc.ant.run.AntRunConfigurationType
import com.github.antsrc.ant.settings.AntExecutable
import com.github.antsrc.ant.settings.AntSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

class AntRunConfigurationTest : BasePlatformTestCase() {
    private var antPath = ""

    override fun setUp() {
        super.setUp()
        antPath = AntSettings.getInstance().antPath
    }

    override fun tearDown() {
        try {
            AntSettings.getInstance().antPath = antPath
        } finally {
            super.tearDown()
        }
    }

    fun testCommandLine() {
        AntSettings.getInstance().antPath = "/opt/ant/bin/ant"
        val type = ConfigurationTypeUtil.findConfigurationType(AntRunConfigurationType::class.java)
        val configuration = type.factory.createTemplateConfiguration(project) as AntRunConfiguration
        configuration.file = "/tmp/dir/main.ant"
        configuration.arguments = "a \"b c\""
        val cmd = configuration.commandLine()
        assertEquals("/opt/ant/bin/ant", cmd.exePath)
        assertEquals(listOf("run", "/tmp/dir/main.ant", "a", "b c"), cmd.parametersList.parameters)
        assertEquals("/tmp/dir", cmd.workDirectory.path)
        configuration.workingDirectory = "/tmp"
        assertEquals("/tmp", configuration.commandLine().workDirectory.path)
        assertEquals("main.ant", configuration.suggestedName())
        try {
            configuration.checkConfiguration()
            fail("a missing file is an error")
        } catch (e: RuntimeConfigurationError) {
            assertTrue(e.message!!.contains("main.ant"))
        }
    }

    fun testProducerFromAntFile() {
        val file = myFixture.addFileToProject("dir/main.ant", "println(1)")
        val context = ConfigurationContext.getFromContext(myFixture.editor?.let { null } ?: run {
            myFixture.openFileInEditor(file.virtualFile)
            com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE, file)
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_ELEMENT, file.firstChild)
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE, file.virtualFile)
                .build()
        }, "test")
        val settings = context.configurationsFromContext?.singleOrNull() ?: throw AssertionError("one configuration from an Ant file")
        val configuration = settings.configuration as AntRunConfiguration
        assertEquals(file.virtualFile.path, configuration.file)
        assertEquals("main.ant", configuration.name)
        assertTrue(configuration.workingDirectory.isNotEmpty())
    }

    fun testExecutableDetection() {
        val dir = Files.createTempDirectory("ant-exe").toFile()
        try {
            val script = File(dir, "ant").apply { writeText("#!/bin/sh\necho apache\n"); setExecutable(true) }
            assertFalse("a script named ant is not the Ant command", AntExecutable.isAnt(script))
            val binary = File(dir, "ant2").apply { writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())); setExecutable(true) }
            assertTrue(AntExecutable.isAnt(binary))
            val plain = File(dir, "ant3").apply { writeText("x"); setExecutable(false) }
            assertFalse(AntExecutable.isAnt(plain))
            AntSettings.getInstance().antPath = script.path
            assertEquals("the setting wins, even for a script", script.toPath(), AntExecutable.find())
        } finally {
            dir.deleteRecursively()
        }
    }
}

class AntRunLineMarkerTest : BasePlatformTestCase() {
    fun testOneMarkerOnTheFirstCodeToken() {
        val file = myFixture.configureByText("main.ant", "// spec: stdlib Net\n//! A line server.\n//!   ant run echo.ant\n\nfunc session(c: int): void {\n  c\n}\nsession(1)\n")
        val contributor = com.github.antsrc.ant.run.AntRunLineMarkerContributor()
        val marked = generateSequence(file.firstChild) { it.nextSibling }.filter { contributor.getInfo(it) != null }.map { it.text }.toList()
        assertEquals(listOf("func"), marked)
    }
}
