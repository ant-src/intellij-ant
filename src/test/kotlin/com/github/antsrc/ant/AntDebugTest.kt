package com.github.antsrc.ant

import com.github.antsrc.ant.debug.AntDapConnection
import com.github.antsrc.ant.run.AntRunConfiguration
import com.github.antsrc.ant.run.AntRunConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments
import org.eclipse.lsp4j.debug.ContinueArguments
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.ScopesArguments
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.debug.SourceBreakpoint
import org.eclipse.lsp4j.debug.StackTraceArguments
import org.eclipse.lsp4j.debug.StoppedEventArguments
import org.eclipse.lsp4j.debug.VariablesArguments
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The debugger's side of ant dap, against the real executable that ANT_PATH names. */
class AntDebugTest : BasePlatformTestCase() {
    fun testLaunchAttributes() {
        val type = ConfigurationTypeUtil.findConfigurationType(AntRunConfigurationType::class.java)
        val configuration = type.factory.createTemplateConfiguration(project) as AntRunConfiguration
        configuration.file = "/tmp/dir/main.ant"
        configuration.arguments = "a \"b c\""
        val attributes = configuration.launchAttributes()
        assertEquals("/tmp/dir/main.ant", attributes["program"])
        assertEquals(listOf("a", "b c"), attributes["args"])
        assertEquals("/tmp/dir", attributes["cwd"])
    }

    fun testBreakpointStackAndVariables() {
        val ant = System.getenv("ANT_PATH")?.takeIf { File(it).canExecute() } ?: return // no ant to run
        val dir = Files.createTempDirectory("ant-debug").toFile()
        val main = File(dir, "main.ant").apply {
            writeText(
                """
                func greet(name: string): string {
                  var text = "hello, " + name
                  return text
                }

                println(greet("ant"))
                """.trimIndent() + "\n"
            )
        }
        val stops = LinkedBlockingQueue<StoppedEventArguments>()
        val initialized = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val output = StringBuffer()
        val connection = AntDapConnection(GeneralCommandLine(ant, "dap").withWorkDirectory(dir), object : AntDapConnection.Events {
            override fun initialized() = initialized.countDown()
            override fun stopped(e: StoppedEventArguments) { stops.add(e) }
            override fun output(category: String, text: String) { output.append(text) }
            override fun terminated() = ended.countDown()
        })
        try {
            connection.launch(mapOf("program" to main.path))
            assertTrue(initialized.await(10, TimeUnit.SECONDS))
            val server = connection.server
            val bps = server.setBreakpoints(SetBreakpointsArguments().apply {
                source = Source().apply { path = main.path }
                breakpoints = arrayOf(SourceBreakpoint().apply { line = 3 })
            }).get(10, TimeUnit.SECONDS)
            assertTrue(bps.breakpoints[0].isVerified)
            server.configurationDone(ConfigurationDoneArguments()).get(10, TimeUnit.SECONDS)

            val stop = stops.poll(10, TimeUnit.SECONDS) ?: error("no stop")
            assertEquals("breakpoint", stop.reason)
            val frames = server.stackTrace(StackTraceArguments().apply { threadId = stop.threadId }).get(10, TimeUnit.SECONDS).stackFrames
            assertEquals("greet", frames[0].name)
            assertEquals(3, frames[0].line)
            assertEquals(main.path, frames[0].source.path)
            val locals = server.scopes(ScopesArguments().apply { frameId = frames[0].id }).get(10, TimeUnit.SECONDS).scopes[0]
            val vars = server.variables(VariablesArguments().apply { variablesReference = locals.variablesReference }).get(10, TimeUnit.SECONDS).variables
            assertEquals(listOf("name=\"ant\"", "text=\"hello, ant\""), vars.map { "${it.name}=${it.value}" })

            server.continue_(ContinueArguments().apply { threadId = stop.threadId }).get(10, TimeUnit.SECONDS)
            assertTrue(ended.await(10, TimeUnit.SECONDS))
            assertEquals("hello, ant\n", output.toString())
            server.disconnect(DisconnectArguments()).get(10, TimeUnit.SECONDS)
        } finally {
            connection.close()
        }
    }
}
