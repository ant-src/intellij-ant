package com.github.antsrc.ant

import com.github.antsrc.ant.run.AntRunConfiguration
import com.github.antsrc.ant.run.AntRunConfigurationType
import com.github.antsrc.ant.run.AntTest
import com.github.antsrc.ant.run.AntTests
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
        // The configured executable must exist; a file stands in for it.
        val ant = File(Files.createTempDirectory("ant-bin").toFile(), "ant").apply { writeText("") }
        AntSettings.getInstance().antPath = ant.path
        AntExecutable.forget()
        val type = ConfigurationTypeUtil.findConfigurationType(AntRunConfigurationType::class.java)
        val configuration = type.factory.createTemplateConfiguration(project) as AntRunConfiguration
        configuration.file = "/tmp/dir/main.ant"
        configuration.arguments = "a \"b c\""
        val cmd = configuration.commandLine()
        assertEquals(ant.path, cmd.exePath)
        assertEquals(listOf("run", "/tmp/dir/main.ant", "a", "b c"), cmd.parametersList.parameters)
        assertEquals("/tmp/dir", cmd.workDirectory.path)
        configuration.workingDirectory = "/tmp"
        assertEquals("/tmp", configuration.commandLine().workDirectory.path)
        assertNull("the console is the input without a file", configuration.commandLine().inputFile)
        configuration.stdinFile = "input.txt"
        assertEquals("the input file is relative to the working directory", File("/tmp", "input.txt"), configuration.commandLine().inputFile)
        configuration.stdinFile = ""
        assertEquals("main.ant", configuration.suggestedName())
        AntSettings.getInstance().antPath = File(Files.createTempDirectory("no-ant").toFile(), "ant").path
        AntExecutable.forget()
        try {
            configuration.commandLine()
            fail("without ant there is no command line")
        } catch (e: com.intellij.execution.ExecutionException) {
            assertTrue(e.message!!.contains("Settings"))
        }
        try {
            configuration.checkConfiguration()
            fail("a missing file is an error")
        } catch (e: RuntimeConfigurationError) {
            assertTrue(e.message!!.contains("main.ant"))
        }
    }

    fun testTestCommandLine() {
        val ant = File(Files.createTempDirectory("ant-bin").toFile(), "ant").apply { writeText("") }
        AntSettings.getInstance().antPath = ant.path
        AntExecutable.forget()
        val type = ConfigurationTypeUtil.findConfigurationType(AntRunConfigurationType::class.java)
        val configuration = type.factory.createTemplateConfiguration(project) as AntRunConfiguration
        configuration.file = "/tmp/dir/stack_test.ant"
        assertEquals(listOf("test", "/tmp/dir/stack_test.ant"), configuration.commandLine().parametersList.parameters)
        configuration.testPattern = "^parse$/^0x10$"
        configuration.arguments = "-v"
        assertEquals("the arguments are flags of ant test", listOf("test", "-run", "^parse$/^0x10$", "-v", "/tmp/dir/stack_test.ant"), configuration.commandLine().parametersList.parameters)
        val attributes = configuration.launchAttributes()
        assertEquals("^parse$/^0x10$", attributes["test"])
        assertFalse("ant dap takes no flags of ant test", attributes.containsKey("args"))
        configuration.file = "/tmp/dir/main.ant"
        assertFalse(configuration.launchAttributes().containsKey("test"))
        try {
            configuration.checkConfiguration()
            fail("tests to run need a test file")
        } catch (e: RuntimeConfigurationError) {
            assertTrue(e.message!!, e.message!!.contains("_test.ant"))
        }
    }

    fun testTestsOfATestFile() {
        val file = myFixture.configureByText(
            "stack_test.ant",
            """
            import "lib"

            test "push" {
              Regex.test(r, "a")
            }

            func helper() {
              test "not a test" {}
            }

            test "a.b (\"c\")" {
              for c in cases {
                test "${'$'}{c}" {
                  test "hidden" {}
                }
                test "0x10" {}
              }
            }
            """.trimIndent(),
        )
        val tests = AntTests.of(file)
        assertEquals(
            listOf("push=^push$", "a.b (\"c\")=^a\\.b \\(\"c\"\\)$", "0x10=^a\\.b \\(\"c\"\\)$/^0x10( #[0-9]+)?$"),
            tests.map { "${it.name}=${it.pattern}" },
        )
        assertEquals(file.text.indexOf("test \"push\""), tests[0].range.startOffset)
        assertEquals(file.text.indexOf("}\n\nfunc") + 1, tests[0].range.endOffset)
        val inner = file.text.indexOf("{}", file.text.indexOf("0x10"))
        assertEquals("0x10", AntTests.around(file, inner)?.name)
        assertEquals("a.b (\"c\")", AntTests.around(file, file.text.indexOf("hidden"))?.name)
        assertNull(AntTests.around(file, file.text.indexOf("helper")))
        assertEquals("^a\\/b$", AntTests.quote("a/b"))
        val plain = myFixture.configureByText("stack.ant", "test \"push\" {}\n")
        assertEquals("only a test file has tests", emptyList<AntTest>(), AntTests.of(plain))
    }

    fun testProducerFromATest() {
        val file = myFixture.addFileToProject("dir/stack_test.ant", "test \"push\" {\n  assert(true)\n}\n")
        myFixture.openFileInEditor(file.virtualFile)
        fun configurationAt(offset: Int): AntRunConfiguration {
            val element = file.findElementAt(offset)!!
            val dataContext = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE, file)
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_ELEMENT, element)
                .add(com.intellij.execution.Location.DATA_KEY, com.intellij.execution.PsiLocation(element))
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE, file.virtualFile)
                .build()
            val context = ConfigurationContext.getFromContext(dataContext, "test")
            val settings = context.configurationsFromContext?.singleOrNull() ?: throw AssertionError("one configuration from a test file")
            return settings.configuration as AntRunConfiguration
        }
        val test = configurationAt(0)
        assertEquals("^push$", test.testPattern)
        assertEquals("stack_test.ant: push", test.name)
        assertEquals("^push$", configurationAt(file.text.indexOf("assert")).testPattern)
        val whole = configurationAt(file.text.length - 1)
        assertEquals("", whole.testPattern)
        assertEquals("stack_test.ant", whole.name)
    }

    fun testProducerFromAntFile() {
        val file = myFixture.addFileToProject("dir/main.ant", "println(1)")
        myFixture.openFileInEditor(file.virtualFile)
        val dataContext = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
            .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
            .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE, file)
            .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_ELEMENT, file.firstChild)
            .add(com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE, file.virtualFile)
            .build()
        val context = ConfigurationContext.getFromContext(dataContext, "test")
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

    fun testMarkersOnTests() {
        val file = myFixture.configureByText("stack_test.ant", "import \"lib\"\n\ntest \"push\" {\n  test \"${'$'}{x}\" {}\n  test \"pop\" {}\n}\n")
        val contributor = com.github.antsrc.ant.run.AntRunLineMarkerContributor()
        val marked = generateSequence(file.firstChild) { it.nextSibling }.filter { contributor.getInfo(it) != null }
            .map { file.text.substring(it.textRange.startOffset, minOf(it.textRange.startOffset + 11, file.textLength)) }.toList()
        assertEquals(listOf("import \"lib", "test \"push\"", "test \"pop\" "), marked)
    }
}
