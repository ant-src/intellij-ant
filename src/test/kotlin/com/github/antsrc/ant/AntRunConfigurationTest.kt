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
        assertEquals(listOf("test", "-json", "/tmp/dir/stack_test.ant"), configuration.commandLine().parametersList.parameters)
        configuration.testPattern = "^parse$/^0x10$"
        configuration.arguments = "-v"
        assertEquals("the arguments are flags of ant test", listOf("test", "-json", "-run", "^parse$/^0x10$", "-v", "/tmp/dir/stack_test.ant"), configuration.commandLine().parametersList.parameters)
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
        val parent = "^a\\.b \\(\"c\"\\)$"
        assertEquals(
            listOf(
                "push=^push$",
                "a.b (\"c\")=$parent",
                "${'$'}{c}=$parent/^.*( #[0-9]+)?$",
                "hidden=$parent/^.*( #[0-9]+)?$/^hidden( #[0-9]+)?$",
                "0x10=$parent/^0x10( #[0-9]+)?$",
            ),
            tests.map { "${it.name}=${it.pattern}" },
        )
        assertEquals(listOf("push", "a.b (\"c\")", null, null, "a.b (\"c\")/0x10"), tests.map { it.fullName })
        assertEquals(file.text.indexOf("test \"push\""), tests[0].range.startOffset)
        assertEquals(file.text.indexOf("}\n\nfunc") + 1, tests[0].range.endOffset)
        val inner = file.text.indexOf("{}", file.text.indexOf("0x10"))
        assertEquals("0x10", AntTests.around(file, inner)?.name)
        assertEquals("hidden", AntTests.around(file, file.text.indexOf("hidden"))?.name)
        assertEquals("0x10", AntTests.find(file, "a.b (\"c\")/0x10 #2")?.name)
        assertEquals("hidden", AntTests.find(file, "a.b (\"c\")/x/hidden")?.name)
        assertEquals("${'$'}{c}", AntTests.find(file, "a.b (\"c\")/x")?.name)
        assertEquals("push", AntTests.find(file, "push/made in a function")?.name)
        assertNull(AntTests.find(file, "pop"))
        assertNull(AntTests.around(file, file.text.indexOf("helper")))
        assertEquals("^a\\/b$", AntTests.quote("a/b"))
        assertEquals("^case .*\\+.*( #[0-9]+)?$", AntTests.template(listOf("case ", "+", ""), subtest = true))
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

        val library = myFixture.addFileToProject("dir/lib.ant", "export func f(): int { 1 }\n")
        val libraryContext = ConfigurationContext.getFromContext(
            com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE, library)
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_ELEMENT, library.firstChild)
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE, library.virtualFile)
                .build(),
            "test",
        )
        assertTrue("a library has nothing to run", libraryContext.configurationsFromContext.isNullOrEmpty())
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

    fun testNoMarkerOnALibrary() {
        val file = myFixture.configureByText("lib.ant", "//! A library.\n\nexport func f(): int { 1 }\n")
        val contributor = com.github.antsrc.ant.run.AntRunLineMarkerContributor()
        assertTrue(generateSequence(file.firstChild) { it.nextSibling }.none { contributor.getInfo(it) != null })
    }

    fun testScripts() {
        var n = 0
        fun script(text: String) = com.github.antsrc.ant.run.AntScripts.isScript(myFixture.configureByText("m${++n}.ant", text) as com.github.antsrc.ant.psi.AntFile)
        assertTrue(script("println(1)\n"))
        assertTrue(script("#!/usr/bin/env ant\nfunc f(): int { 1 }\n"))
        assertTrue(script("import \"./a.ant\" { a }\n\nvar x = 1\nfor i in [1] {\n  println(i)\n}\n"))
        assertTrue(script("var page = <div>\n  hello\n</div>\nprintln(page)\n"))
        assertFalse(script(""))
        assertFalse(script("// only a comment\n"))
        assertFalse(script("export let LIMIT = 10\nlet origin = 0\n"))
        assertFalse(script("import \"./a.ant\" { a }\n\nexport func f(x: int): int {\n  println(x)\n  x\n}\n"))
        assertFalse(script("var total =\n  1 +\n  2\nvar names = [1, 2]\n  |> List.map(f)\n  .len()\nexport type T = {\n  a: int,\n}\n"))
        assertFalse(script("var page = <div>\n  hello {name}\n</div>\n"))
        assertFalse(script("func f(): void {\n  if true {\n  }\n  else {\n  }\n}\n"))
    }

    fun testMarkersOnTests() {
        val file = myFixture.configureByText("stack_test.ant", "import \"lib\"\n\ntest \"push\" {\n  test \"${'$'}{x}\" {}\n  test \"pop\" {}\n}\n")
        val contributor = com.github.antsrc.ant.run.AntRunLineMarkerContributor()
        val marked = generateSequence(file.firstChild) { it.nextSibling }.filter { contributor.getInfo(it) != null }
            .map { file.text.substring(it.textRange.startOffset, minOf(it.textRange.startOffset + 11, file.textLength)) }.toList()
        assertEquals(listOf("import \"lib", "test \"push\"", "test \"${'$'}{x}\"", "test \"pop\" "), marked)
    }

    fun testTableCases() {
        val file = myFixture.configureByText(
            "sum_test.ant",
            """
            test "table" {
              for { a, b } in cases {
                test "case ${'$'}{a}+${'$'}{"${'$'}{b}"}" {
                  assert(sum(a, b) == a + b)
                }
              }
            }
            """.trimIndent(),
        )
        val case = AntTests.of(file)[1]
        assertEquals("case ${'$'}{a}+${'$'}{\"${'$'}{b}\"}", case.name)
        assertEquals("^table$/^case .*\\+.*( #[0-9]+)?$", case.pattern)
        assertTrue(case.fullNames.matches("table/case 1+2 #2"))
        assertFalse(case.fullNames.matches("table/other"))
    }

    fun testRowsOfATable() {
        val file = myFixture.configureByText(
            "sum_test.ant",
            """
            test "table" {
              type Case = { a: int, b: int, name: string, ok: bool = true }
              let cases: Array[{ a: int, b: int }] = [
                Case{ a: 1, b: 2, name: "one" },
                { a: -3, b: f(1, 2) },
                Case{ a: 2, b: 3, name: "a/b" },
              ]
              for { a, b: second } in cases {
                test "case ${'$'}{a}+${'$'}{second}" {}
              }
              for c in cases {
                test "${'$'}{c.name}: ${'$'}{c.a}" {}
              }
              for c in cases {
                test "${'$'}{c.a + 1}" {}
              }
            }
            """.trimIndent(),
        )
        val rows = AntTests.of(file).filter { it.keyword.text == "Case" || it.keyword.text == "{" }
        assertEquals(
            listOf(
                "case 1+2=^table$/^case 1\\+2( #[0-9]+)?$",
                "one: 1=^table$/^one: 1( #[0-9]+)?$",
                "case 2+3=^table$/^case 2\\+3( #[0-9]+)?$",
                "a/b: 2=^table$/^a\\/b: 2( #[0-9]+)?$",
            ),
            rows.map { "${it.name}=${it.pattern}" },
        )
        assertEquals("table/case 1+2", rows[0].fullName)
        val second = file.text.indexOf("Case{ a: 2")
        assertEquals("the row is a test of its own", "case 2+3", AntTests.at(file.findElementAt(second)!!)?.name)
        assertEquals("case 1+2", AntTests.find(file, "table/case 1+2 #2")?.name)
        assertEquals("a row of no known name runs with its template", "case ${'$'}{a}+${'$'}{second}", AntTests.find(file, "table/case -3+7")?.name)
        val contributor = com.github.antsrc.ant.run.AntRunLineMarkerContributor()
        assertNotNull(contributor.getInfo(file.findElementAt(second)!!))
        assertNull("no marker on a row whose name is not known", contributor.getInfo(file.findElementAt(file.text.indexOf("{ a: -3"))!!))
    }

    fun testTheTableInScope() {
        val file = myFixture.configureByText(
            "scope_test.ant",
            """
            var cases = [{ n: 1 }]

            test "a" {
              var cases = [{ n: 2 }]
              for c in cases {
                test "a ${'$'}{c.n}" {}
              }
            }

            test "b" {
              for c in cases {
                test "b ${'$'}{c.n}" {}
              }
            }
            """.trimIndent(),
        )
        val rows = AntTests.of(file).filter { it.keyword.text == "{" }.map { it.pattern }
        assertEquals("each template's rows are of the table in its scope", listOf("^b$/^b 1( #[0-9]+)?$", "^a$/^a 2( #[0-9]+)?$"), rows)
    }
}

class AntTestEventsTest : BasePlatformTestCase() {
    /** What `ant test -json x_test.ant` wrote, run from /w. */
    private val output = """
        {"action":"run","file":"x_test.ant","test":"bad"}
        {"action":"output","file":"x_test.ant","test":"bad","output":"hello\n"}
        {"action":"fail","file":"x_test.ant","test":"bad","elapsed":0.000060042,"message":"assertion failed: 1 == 2 (1 == 2)\n  at test \"bad\" (x_test.ant:7:9)"}
        {"action":"run","file":"x_test.ant","test":"table"}
        {"action":"run","file":"x_test.ant","test":"table/case 1+2"}
        {"action":"pass","file":"x_test.ant","test":"table/case 1+2","elapsed":0.0021}
        {"action":"run","file":"x_test.ant","test":"table/a/b"}
        {"action":"pass","file":"x_test.ant","test":"table/a/b","elapsed":0.000010041}
        {"action":"run","file":"x_test.ant","test":"table/skipped"}
        {"action":"skip","file":"x_test.ant","test":"table/skipped","elapsed":0.000012834,"reason":"later"}
        {"action":"fail","file":"x_test.ant","test":"table","elapsed":0.000154042}
        {"action":"done","elapsed":0.000706333,"passed":3,"failed":2,"skipped":1}
    """.trimIndent()

    fun testEventsBecomeServiceMessages() {
        val events = com.github.antsrc.ant.run.AntTestEvents("/w")
        val messages = output.lines().flatMap { events.translate(it)!! }.map { jetbrains.buildServer.messages.serviceMessages.ServiceMessage.parse(it)!! }
        fun show(m: jetbrains.buildServer.messages.serviceMessages.ServiceMessage) =
            m.messageName + listOf("name", "nodeId", "parentNodeId", "locationHint", "out", "message", "duration")
                .mapNotNull { k -> m.attributes[k]?.let { "$k=$it" } }.joinToString(" ", " ")
        assertEquals(
            listOf(
                "testStarted name=bad nodeId=1 parentNodeId=0 locationHint=ant:///w/x_test.ant#bad",
                "testStdOut name=bad nodeId=1 out=hello\n",
                "testFailed name=bad nodeId=1 message=assertion failed: 1 == 2 (1 == 2)\n  at test \"bad\" (x_test.ant:7:9)",
                "testFinished name=bad nodeId=1 duration=0",
                "testStarted name=table nodeId=2 parentNodeId=0 locationHint=ant:///w/x_test.ant#table",
                "testStarted name=case 1+2 nodeId=3 parentNodeId=2 locationHint=ant:///w/x_test.ant#table/case 1+2",
                "testFinished name=table/case 1+2 nodeId=3 duration=2",
                "testStarted name=a/b nodeId=4 parentNodeId=2 locationHint=ant:///w/x_test.ant#table/a/b",
                "testFinished name=table/a/b nodeId=4 duration=0",
                "testStarted name=skipped nodeId=5 parentNodeId=2 locationHint=ant:///w/x_test.ant#table/skipped",
                "testIgnored name=table/skipped nodeId=5 message=later",
                "testFinished name=table/skipped nodeId=5 duration=0",
                "testFailed name=table nodeId=2 message=",
                "testFinished name=table nodeId=2 duration=0",
            ),
            messages.map(::show),
        )
        assertNull("other output is not an event", events.translate("x_test.ant:1:1: unexpected character"))
    }

    fun testTree() {
        val root = com.intellij.execution.testframework.sm.runner.SMTestProxy.SMRootTestProxy()
        val processor = com.intellij.execution.testframework.sm.runner.GeneralIdBasedToSMTRunnerEventsConvertor(project, root, "Ant")
        val type = ConfigurationTypeUtil.findConfigurationType(AntRunConfigurationType::class.java)
        val configuration = type.factory.createTemplateConfiguration(project) as AntRunConfiguration
        val properties = com.github.antsrc.ant.run.AntTestConsoleProperties(configuration, com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance(), "/w")
        val converter = properties.createTestEventsConverter("Ant", properties)
        converter.setProcessor(processor)
        processor.onStartTesting()
        for (line in output.lines()) converter.process(line + "\n", com.intellij.execution.process.ProcessOutputTypes.STDOUT)
        converter.flushBufferOnProcessTermination(1)
        processor.onFinishTesting()
        fun show(t: com.intellij.execution.testframework.AbstractTestProxy): String =
            t.name + ":" + (if (t.isPassed) "passed" else if (t.isIgnored) "ignored" else "failed") +
                t.children.joinToString(",", "(", ")") { show(it) }.takeIf { t.children.isNotEmpty() }.orEmpty()
        assertEquals(listOf("bad:failed", "table:failed(case 1+2:passed,a/b:passed,skipped:ignored)"), root.children.map(::show))
        assertEquals("ant:///w/x_test.ant#table/case 1+2", root.children[1].children[0].locationUrl)
    }
}
