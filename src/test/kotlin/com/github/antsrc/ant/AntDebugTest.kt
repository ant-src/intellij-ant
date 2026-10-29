package com.github.antsrc.ant

import com.github.antsrc.ant.debug.AntDapConnection
import com.github.antsrc.ant.debug.AntPanicBreakpointType
import com.github.antsrc.ant.debug.AntPanicProperties
import com.github.antsrc.ant.debug.fieldPathAt
import com.github.antsrc.ant.run.AntRunConfiguration
import com.github.antsrc.ant.run.AntRunConfigurationType
import com.github.antsrc.ant.settings.AntExecutable
import com.github.antsrc.ant.settings.AntSettings
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments
import org.eclipse.lsp4j.debug.ContinueArguments
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.EvaluateArguments
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
        assertFalse("no stdin attribute without a file", attributes.containsKey("stdin"))
        configuration.stdinFile = "input.txt"
        assertEquals("a relative file is resolved against the working directory", "/tmp/dir/input.txt", configuration.launchAttributes()["stdin"])
        configuration.stdinFile = "/else/input.txt"
        assertEquals("/else/input.txt", configuration.launchAttributes()["stdin"])
    }

    fun testFieldPathAt() {
        val text = "println(user.address.city + f(x))"
        fun at(word: String) = fieldPathAt(text, text.indexOf(word))?.let { text.substring(it.startOffset, it.endOffset) }
        assertEquals("user.address.city", at("city"))
        assertEquals("user.address", at("address"))
        assertEquals("user", at("user"))
        assertEquals("x", at("x)"))
        assertNull(at("("))
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
            val evaluated = server.evaluate(EvaluateArguments().apply {
                expression = "text + \"!\""
                frameId = frames[0].id
                context = "watch"
            }).get(10, TimeUnit.SECONDS)
            assertEquals("\"hello, ant!\"", evaluated.result)

            server.continue_(ContinueArguments().apply { threadId = stop.threadId }).get(10, TimeUnit.SECONDS)
            assertTrue(ended.await(10, TimeUnit.SECONDS))
            assertEquals("hello, ant\n", output.toString())
            server.disconnect(DisconnectArguments()).get(10, TimeUnit.SECONDS)
        } finally {
            connection.close()
        }
    }

    fun testDebugSessionPrintsToConsole() {
        val ant = System.getenv("ANT_PATH")?.takeIf { File(it).canExecute() } ?: return // no ant to run
        AntSettings.getInstance().antPath = ant
        AntExecutable.forget()
        val dir = Files.createTempDirectory("ant-console").toFile()
        val main = File(dir, "main.ant").apply { writeText("println(\"hello from debug\")\n") }
        val type = ConfigurationTypeUtil.findConfigurationType(AntRunConfigurationType::class.java)
        val settings = RunManager.getInstance(project).createConfiguration("main", type.factory)
        (settings.configuration as AntRunConfiguration).file = main.path
        val environment = ExecutionEnvironmentBuilder.create(DefaultDebugExecutor.getDebugExecutorInstance(), settings).build()
        val started = CountDownLatch(1)
        var session: XDebugSession? = null
        project.messageBus.connect(testRootDisposable).subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
            override fun processStarted(debugProcess: com.intellij.xdebugger.XDebugProcess) {
                session = debugProcess.session
                started.countDown()
            }
        })
        environment.runner.execute(environment)
        val deadline = System.currentTimeMillis() + 20_000
        while (started.count > 0 && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        val console = session?.consoleView as? ConsoleViewImpl ?: error("no console")
        val handler = session!!.debugProcess.processHandler
        while (!handler.isProcessTerminated && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        console.flushDeferredText()
        val text = console.editor?.document?.text ?: console.text
        assertTrue(text, text.contains("hello from debug"))
        assertTrue(text, text.contains("Process finished with exit code 0"))
        Disposer.dispose(console)
    }

    fun testPanicBreakpointStopsTheSession() {
        val ant = System.getenv("ANT_PATH")?.takeIf { File(it).canExecute() } ?: return // no ant to run
        AntSettings.getInstance().antPath = ant
        AntExecutable.forget()
        val dir = Files.createTempDirectory("ant-panic").toFile()
        val main = File(dir, "main.ant").apply { writeText("var xs = [1]\nprintln(xs[5])\n") }
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        val type = XDebuggerUtil.getInstance().findBreakpointType(AntPanicBreakpointType::class.java)
        manager.getBreakpoints(type).forEach { com.intellij.openapi.application.runWriteAction { manager.removeBreakpoint(it) } }
        val bp = com.intellij.openapi.application.runWriteAction { manager.addBreakpoint(type, AntPanicProperties()) }
        try {
            val session = startDebugging(main)
            waitFor("a stop at the panic") { session.isSuspended }
            assertEquals(2, session.currentPosition?.line?.plus(1))
            session.stop()
            waitFor("the end of the session") { session.debugProcess.processHandler.isProcessTerminated }
            Disposer.dispose(session.consoleView)
        } finally {
            com.intellij.openapi.application.runWriteAction { manager.removeBreakpoint(bp) }
        }
    }

    private fun startDebugging(file: File): XDebugSession {
        val type = ConfigurationTypeUtil.findConfigurationType(AntRunConfigurationType::class.java)
        val settings = RunManager.getInstance(project).createConfiguration(file.name, type.factory)
        (settings.configuration as AntRunConfiguration).file = file.path
        val environment = ExecutionEnvironmentBuilder.create(DefaultDebugExecutor.getDebugExecutorInstance(), settings).build()
        var session: XDebugSession? = null
        val connection = project.messageBus.connect(testRootDisposable)
        connection.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
            override fun processStarted(debugProcess: com.intellij.xdebugger.XDebugProcess) {
                session = debugProcess.session
            }
        })
        environment.runner.execute(environment)
        waitFor("the session") { session != null }
        return session!!
    }

    private fun waitFor(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!done()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
    }
}
