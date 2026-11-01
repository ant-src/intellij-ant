package com.github.antsrc.ant.run

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.Executor
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.ServiceMessageBuilder
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageVisitor
import java.io.File

/**
 * Where a test is, for the test tree and for the state the IDE keeps of each test's last result:
 * `ant://<path of its file>#<its full name>`.
 */
object AntTestLocator : SMTestLocator {
    const val PROTOCOL = "ant"

    fun url(path: String, fullName: String) = "$PROTOCOL://$path#$fullName"

    override fun getLocation(protocol: String, path: String, project: Project, scope: GlobalSearchScope): List<Location<*>> {
        if (protocol != PROTOCOL) return emptyList()
        val hash = path.indexOf('#')
        if (hash < 0) return emptyList()
        val file = LocalFileSystem.getInstance().findFileByPath(path.substring(0, hash)) ?: return emptyList()
        val psi = PsiManager.getInstance(project).findFile(file) ?: return emptyList()
        val test = AntTests.find(psi, path.substring(hash + 1))
        return listOf(PsiLocation(test?.keyword ?: psi))
    }
}

/** The test tree of `ant test -json` (test.md section 2), built by node ids, as subtests nest. */
class AntTestConsoleProperties(configuration: AntRunConfiguration, executor: Executor, private val directory: String) :
    SMTRunnerConsoleProperties(configuration, FRAMEWORK, executor), SMCustomMessagesParsing {
    init {
        isIdBasedTestTree = true
    }

    override fun getTestLocator(): SMTestLocator = AntTestLocator

    override fun createTestEventsConverter(testFrameworkName: String, consoleProperties: TestConsoleProperties): OutputToGeneralTestEventsConverter =
        AntTestEventsConverter(testFrameworkName, consoleProperties, directory)

    companion object {
        const val FRAMEWORK = "Ant"
    }
}

/** Reads the events `ant test -json` writes, one per line on standard output; the rest is output as it is. */
class AntTestEventsConverter(testFrameworkName: String, consoleProperties: TestConsoleProperties, directory: String) :
    OutputToGeneralTestEventsConverter(testFrameworkName, consoleProperties) {
    private val events = AntTestEvents(directory)

    override fun processServiceMessages(text: String, outputType: Key<*>, visitor: ServiceMessageVisitor): Boolean {
        if (outputType != ProcessOutputTypes.STDOUT) return super.processServiceMessages(text, outputType, visitor)
        val messages = events.translate(text) ?: return super.processServiceMessages(text, outputType, visitor)
        for (message in messages) super.processServiceMessages(message, outputType, visitor)
        return true
    }
}

/**
 * Turns the events of `ant test -json` into the service messages of the IDE's test runner. A test
 * starts under the innermost test still running whose full name and a `/` begin its own: a
 * subtest's full name is its parent's, `/`, and its name (ant-lang.md section 10, Tests). A file
 * named relative to the run's working directory is resolved against it.
 */
class AntTestEvents(private val directory: String) {
    private class Node(val id: String, val file: String, val fullName: String)

    private val running = ArrayList<Node>()
    private var lastId = 0

    /** The service messages of one line, or null when it is not an event. */
    fun translate(line: String): List<String>? {
        val event = try {
            JsonParser.parseString(line.trim()).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val action = event.string("action") ?: return null
        if (action == "done") return emptyList()
        val file = event.string("file")?.let(::resolve) ?: return null
        val test = event.string("test") ?: return null
        if (action == "run") {
            val parent = running.filter { it.file == file && test.startsWith(it.fullName + "/") }.maxByOrNull { it.fullName.length }
            val node = Node((++lastId).toString(), file, test)
            running.add(node)
            return listOf(
                ServiceMessageBuilder.testStarted(parent?.let { test.substring(it.fullName.length + 1) } ?: test)
                    .addAttribute("nodeId", node.id)
                    .addAttribute("parentNodeId", parent?.id ?: "0")
                    .addAttribute("locationHint", AntTestLocator.url(file, test))
                    .toString(),
            )
        }
        val node = running.lastOrNull { it.file == file && it.fullName == test } ?: return emptyList()
        fun message(builder: ServiceMessageBuilder) = builder.addAttribute("nodeId", node.id).toString()
        fun finished(): String {
            running.remove(node)
            val elapsed = event.get("elapsed")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
            return message(ServiceMessageBuilder.testFinished(node.fullName).addAttribute("duration", (elapsed * 1000).toLong().toString()))
        }
        return when (action) {
            "output" -> listOf(message(ServiceMessageBuilder.testStdOut(test).addAttribute("out", event.string("output") ?: "")))
            "pass" -> listOf(finished())
            "skip" -> listOf(message(ServiceMessageBuilder.testIgnored(test).addAttribute("message", event.string("reason") ?: "")), finished())
            // A parent that fails only because a subtest did has no message of its own.
            "fail" -> listOf(message(ServiceMessageBuilder.testFailed(test).addAttribute("message", event.string("message") ?: "")), finished())
            else -> emptyList()
        }
    }

    private fun resolve(path: String): String =
        FileUtil.toSystemIndependentName((File(path).takeIf { it.isAbsolute } ?: File(directory, path)).normalize().path)

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
}
