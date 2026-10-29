package com.github.antsrc.ant.debug

import com.github.antsrc.ant.AntFileType
import com.github.antsrc.ant.run.AntRunConfiguration
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.filters.RegexpFilter
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XNamedValue
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueGroup
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValueModifier
import com.intellij.xdebugger.frame.XValuePlace
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments
import org.eclipse.lsp4j.debug.ContinueArguments
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.EvaluateArguments
import org.eclipse.lsp4j.debug.NextArguments
import org.eclipse.lsp4j.debug.PauseArguments
import org.eclipse.lsp4j.debug.ScopesArguments
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments
import org.eclipse.lsp4j.debug.SetVariableArguments
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.debug.SourceBreakpoint
import org.eclipse.lsp4j.debug.StackTraceArguments
import org.eclipse.lsp4j.debug.StepInArguments
import org.eclipse.lsp4j.debug.StepOutArguments
import org.eclipse.lsp4j.debug.StoppedEventArguments
import org.eclipse.lsp4j.debug.TerminateArguments
import org.eclipse.lsp4j.debug.Variable
import org.eclipse.lsp4j.debug.VariablesArguments
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.eclipse.lsp4j.debug.StackFrame as DapFrame

/** A debug session of an Ant program, run by ant dap (spec/dap.md). */
class AntDebugProcess(session: XDebugSession, private val configuration: AntRunConfiguration) : XDebugProcess(session) {
    private val handler: AntDapProcessHandler = AntDapProcessHandler({ terminate() }, { disconnect() })
    private val connection = AntDapConnection(configuration.debugCommandLine(), Events())
    private val server get() = connection.server

    /** The line breakpoints of each file, by path. */
    private val breakpoints = ConcurrentHashMap<String, MutableSet<XLineBreakpoint<AntBreakpointProperties>>>()
    @Volatile private var launched = false

    private val breakpointHandler = object : XBreakpointHandler<XLineBreakpoint<AntBreakpointProperties>>(AntLineBreakpointType::class.java) {
        override fun registerBreakpoint(breakpoint: XLineBreakpoint<AntBreakpointProperties>) {
            val path = breakpoint.sourcePosition?.file?.path ?: return
            breakpoints.computeIfAbsent(path) { ConcurrentHashMap.newKeySet() }.add(breakpoint)
            if (launched) background { sendBreakpoints(path) }
        }

        override fun unregisterBreakpoint(breakpoint: XLineBreakpoint<AntBreakpointProperties>, temporary: Boolean) {
            val path = breakpoint.sourcePosition?.file?.path ?: return
            breakpoints[path]?.remove(breakpoint)
            if (launched) background { sendBreakpoints(path) }
        }
    }

    /** The panic breakpoints, which together say which panic filters ant dap gets. */
    private val panicBreakpoints = ConcurrentHashMap.newKeySet<XBreakpoint<AntPanicProperties>>()

    private val panicHandler = object : XBreakpointHandler<XBreakpoint<AntPanicProperties>>(AntPanicBreakpointType::class.java) {
        override fun registerBreakpoint(breakpoint: XBreakpoint<AntPanicProperties>) {
            panicBreakpoints.add(breakpoint)
            if (launched) background { sendPanicFilters() }
        }

        override fun unregisterBreakpoint(breakpoint: XBreakpoint<AntPanicProperties>, temporary: Boolean) {
            panicBreakpoints.remove(breakpoint)
            if (launched) background { sendPanicFilters() }
        }
    }

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = arrayOf(breakpointHandler, panicHandler)

    /** Sends the panic filters the enabled panic breakpoints ask for (spec/dap.md section 5). */
    private fun sendPanicFilters() {
        val filters = buildList {
            if (panicBreakpoints.isNotEmpty()) add("uncaught")
            if (panicBreakpoints.any { it.properties.caught }) add("all")
        }
        server.setExceptionBreakpoints(SetExceptionBreakpointsArguments().apply { this.filters = filters.toTypedArray() }).get(TIMEOUT, TimeUnit.SECONDS)
    }
    override fun getEditorsProvider(): XDebuggerEditorsProvider = EditorsProvider
    override fun doGetProcessHandler(): ProcessHandler = handler

    /**
     * The program's output goes straight into the console: the debugger shows the console this
     * creates without attaching it to the process handler, whose text would go nowhere.
     */
    private val console: ConsoleView by lazy {
        TextConsoleBuilderFactory.getInstance().createBuilder(session.project).console.also {
            // ant prints path:line:col for an error, which opens the file at the position.
            it.addMessageFilter(RegexpFilter(session.project, "${RegexpFilter.FILE_PATH_MACROS}:${RegexpFilter.LINE_MACROS}:${RegexpFilter.COLUMN_MACROS}"))
        }
    }

    override fun createConsole(): ExecutionConsole = console

    override fun sessionInitialized() {
        handler.startNotify()
        background {
            try {
                connection.launch(configuration.launchAttributes())
            } catch (e: Exception) {
                val message = (e.cause ?: e).message ?: e.toString()
                console.print("$message\n", ConsoleViewContentType.ERROR_OUTPUT)
                connection.close()
                finish(2)
            }
        }
    }

    private inner class Events : AntDapConnection.Events {
        override fun initialized() = background {
            launched = true
            for (path in breakpoints.keys) sendBreakpoints(path)
            sendPanicFilters()
            server.configurationDone(ConfigurationDoneArguments()).get(TIMEOUT, TimeUnit.SECONDS)
        }

        override fun stopped(e: StoppedEventArguments) = background { suspend(e) }

        override fun output(category: String, text: String) {
            val type = when (category) {
                "stdout" -> ConsoleViewContentType.NORMAL_OUTPUT
                "stderr" -> ConsoleViewContentType.ERROR_OUTPUT
                else -> ConsoleViewContentType.LOG_INFO_OUTPUT
            }
            console.print(text, type)
        }

        override fun exited(code: Int) {
            exitCode = code
        }

        override fun terminated() {
            background {
                runCatching { server.disconnect(DisconnectArguments()).get(2, TimeUnit.SECONDS) }
                connection.close()
                finish(exitCode)
            }
        }
    }

    @Volatile private var exitCode = 0

    /** Sends the breakpoints of one file, and shows which ant dap could not bind. */
    private fun sendBreakpoints(path: String) {
        val bps = breakpoints[path]?.toList() ?: emptyList()
        val response = server.setBreakpoints(SetBreakpointsArguments().apply {
            source = Source().apply { this.path = path }
            breakpoints = bps.map { bp ->
                SourceBreakpoint().apply {
                    line = bp.line + 1
                    condition = bp.conditionExpression?.expression?.takeIf { it.isNotBlank() }
                    // The IDE logs an expression's value; ant dap logs a message with {expr} parts.
                    logMessage = bp.logExpressionObject?.expression?.takeIf { it.isNotBlank() }?.let { "{$it}" }
                }
            }.toTypedArray()
        }).get(TIMEOUT, TimeUnit.SECONDS)
        response.breakpoints.zip(bps).forEach { (result, bp) ->
            if (result.isVerified) {
                session.setBreakpointVerified(bp)
            } else {
                session.setBreakpointInvalid(bp, result.message)
            }
        }
    }

    /** Builds the suspend context of a stop: every goroutine with its frames. */
    private fun suspend(e: StoppedEventArguments) {
        val threads = server.threads().get(TIMEOUT, TimeUnit.SECONDS).threads
        val stacks = threads.map { t ->
            val frames = server.stackTrace(StackTraceArguments().apply { threadId = t.id }).get(TIMEOUT, TimeUnit.SECONDS).stackFrames
            AntExecutionStack(t.id, t.name, frames.map { AntStackFrame(it) })
        }
        val active = stacks.firstOrNull { it.threadId == e.threadId } ?: stacks.firstOrNull() ?: return
        val context = AntSuspendContext(stacks, active)
        if (e.reason == "exception") {
            console.print("${e.description ?: "panic"}\n", ConsoleViewContentType.ERROR_OUTPUT)
        }
        val top = active.frames.firstOrNull()?.frame
        val hit = if (e.reason == "breakpoint" && top?.source?.path != null) {
            breakpoints[top.source.path]?.firstOrNull { it.line + 1 == top.line }
        } else null
        if (hit != null) {
            if (!session.breakpointReached(hit, null, context)) resume(context)
        } else {
            session.positionReached(context)
        }
    }

    private fun threadOf(context: XSuspendContext?) = (context?.activeExecutionStack as? AntExecutionStack)?.threadId ?: 1

    override fun resume(context: XSuspendContext?) = background {
        server.continue_(ContinueArguments().apply { threadId = threadOf(context) })
    }

    override fun startStepOver(context: XSuspendContext?) = background {
        server.next(NextArguments().apply { threadId = threadOf(context) })
    }

    override fun startStepInto(context: XSuspendContext?) = background {
        server.stepIn(StepInArguments().apply { threadId = threadOf(context) })
    }

    override fun startStepOut(context: XSuspendContext?) = background {
        server.stepOut(StepOutArguments().apply { threadId = threadOf(context) })
    }

    override fun startPausing() = background {
        server.pause(PauseArguments().apply { threadId = 1 })
    }

    override fun stop() {
        disconnect()
    }

    private fun terminate(): Unit = background {
        runCatching { server.terminate(TerminateArguments()).get(TIMEOUT, TimeUnit.SECONDS) }
    }

    private fun disconnect(): Unit = background {
        runCatching { server.disconnect(DisconnectArguments()).get(2, TimeUnit.SECONDS) }
        connection.close()
        finish(exitCode)
    }

    /** Ends the session's process, saying so in the console once. */
    private val finished = AtomicBoolean()

    private fun finish(code: Int) {
        if (!finished.compareAndSet(false, true)) return
        console.print("\nProcess finished with exit code $code\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        handler.finish(code)
    }

    private fun background(f: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                f()
            } catch (e: Exception) {
                LOG.warn("ant dap request failed", e)
            }
        }
    }

    // ---- the stopped program

    private inner class AntSuspendContext(private val stacks: List<AntExecutionStack>, private val active: AntExecutionStack) : XSuspendContext() {
        override fun getActiveExecutionStack() = active
        override fun getExecutionStacks(): Array<XExecutionStack> = stacks.toTypedArray()
    }

    private inner class AntExecutionStack(val threadId: Int, name: String, val frames: List<AntStackFrame>) : XExecutionStack(name) {
        override fun getTopFrame() = frames.firstOrNull()
        override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer) {
            container.addStackFrames(frames.drop(firstFrameIndex), true)
        }
    }

    private inner class AntStackFrame(val frame: DapFrame) : XStackFrame() {
        private val position: XSourcePosition? by lazy {
            frame.source?.path?.let { LocalFileSystem.getInstance().findFileByPath(it) }
                ?.let { XDebuggerUtil.getInstance().createPosition(it, frame.line - 1) }
        }

        override fun getSourcePosition() = position

        override fun getEvaluator(): XDebuggerEvaluator = AntEvaluator(frame.id)

        override fun customizePresentation(component: ColoredTextContainer) {
            component.append(frame.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            val file = frame.source?.path?.let { java.io.File(it).name } ?: return
            component.append(" $file:${frame.line}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }

        override fun computeChildren(node: XCompositeNode): Unit = background {
            val scopes = server.scopes(ScopesArguments().apply { frameId = frame.id }).get(TIMEOUT, TimeUnit.SECONDS).scopes
            val list = XValueChildrenList()
            for (scope in scopes) {
                if (scope.name == "Locals") {
                    variables(scope.variablesReference, 0, 0).forEach { list.add(AntValue(it, scope.variablesReference)) }
                } else {
                    list.addBottomGroup(AntScope(scope.name, scope.variablesReference))
                }
            }
            node.addChildren(list, true)
        }
    }

    private fun variables(ref: Int, start: Int, count: Int): List<Variable> =
        server.variables(VariablesArguments().apply {
            variablesReference = ref
            if (count > 0) {
                this.start = start
                this.count = count
            }
        }).get(TIMEOUT, TimeUnit.SECONDS).variables.toList()

    private inner class AntScope(name: String, private val ref: Int) : XValueGroup(name) {
        override fun computeChildren(node: XCompositeNode): Unit = background {
            val list = XValueChildrenList()
            variables(ref, 0, 0).forEach { list.add(AntValue(it, ref)) }
            node.addChildren(list, true)
        }
    }

    /** Evaluates in a frame through ant dap (spec/dap.md section 6). */
    private inner class AntEvaluator(private val frameId: Int) : XDebuggerEvaluator() {
        override fun evaluate(expression: String, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) = background {
            try {
                val r = server.evaluate(EvaluateArguments().apply {
                    this.expression = expression
                    this.frameId = this@AntEvaluator.frameId
                    context = "watch"
                }).get(TIMEOUT, TimeUnit.SECONDS)
                callback.evaluated(AntValue(Variable().apply {
                    name = expression
                    value = r.result
                    type = r.type
                    variablesReference = r.variablesReference
                    indexedVariables = r.indexedVariables
                    namedVariables = r.namedVariables
                }, 0))
            } catch (e: Exception) {
                callback.errorOccurred(((e.cause ?: e).message ?: e.toString()).removePrefix("org.eclipse.lsp4j.jsonrpc.ResponseErrorException: "))
            }
        }

        /** Hover shows a name or a field path, never a call, as ant dap's hover does. */
        override fun getExpressionRangeAtOffset(project: Project, document: Document, offset: Int, sideEffectsAllowed: Boolean): TextRange? =
            fieldPathAt(document.charsSequence, offset)
    }

    /** A variable, in the container its parent reference names, or 0 for an evaluation's result. */
    private inner class AntValue(private val v: Variable, private val parent: Int) : XNamedValue(v.name) {
        /** Set Value evaluates the text in the frame and gives the variable that value (spec/dap.md section 6). */
        override fun getModifier(): XValueModifier? {
            if (parent == 0 || v.presentationHint?.attributes?.contains("readOnly") == true) return null
            return object : XValueModifier() {
                override fun setValue(expression: XExpression, callback: XModificationCallback) = background {
                    try {
                        server.setVariable(SetVariableArguments().apply {
                            variablesReference = parent
                            name = v.name
                            value = expression.expression
                        }).get(TIMEOUT, TimeUnit.SECONDS)
                        callback.valueModified()
                    } catch (e: Exception) {
                        callback.errorOccurred(((e.cause ?: e).message ?: e.toString()))
                    }
                }
            }
        }

        private var loaded = 0 // indexed children shown so far

        override fun computePresentation(node: XValueNode, place: XValuePlace) {
            node.setPresentation(null, v.type, v.value, v.variablesReference > 0)
        }

        override fun computeChildren(node: XCompositeNode): Unit = background {
            val indexed = v.indexedVariables ?: 0
            val list = XValueChildrenList()
            if (indexed > 0) {
                val page = variables(v.variablesReference, loaded, PAGE)
                page.forEach { list.add(AntValue(it, v.variablesReference)) }
                loaded += page.size
                val remaining = indexed - loaded
                node.addChildren(list, remaining <= 0)
                if (remaining > 0) node.tooManyChildren(remaining) { computeChildren(node) }
            } else {
                variables(v.variablesReference, 0, 0).forEach { list.add(AntValue(it, v.variablesReference)) }
                node.addChildren(list, true)
            }
        }
    }

    private object EditorsProvider : XDebuggerEditorsProvider() {
        override fun getFileType() = AntFileType
        override fun createDocument(project: Project, expression: XExpression, sourcePosition: XSourcePosition?, mode: EvaluationMode): Document =
            EditorFactory.getInstance().createDocument(expression.expression)
    }

    companion object {
        private val LOG = logger<AntDebugProcess>()
        private const val TIMEOUT = AntDapConnection.TIMEOUT
        private const val PAGE = 100
    }
}

/**
 * The name or field path, such as `user.address.city`, that ends with the name at offset. A name
 * after a dot belongs to what is before the dot, so when that is not a name, as `name` in
 * `xs[0].name` or `f(x).name`, there is nothing hover can show: the name alone would be another
 * variable.
 */
fun fieldPathAt(text: CharSequence, offset: Int): TextRange? {
    fun ident(c: Char) = c == '_' || c.isLetterOrDigit()
    if (offset >= text.length || !ident(text[offset])) return null
    var end = offset
    while (end < text.length && ident(text[end])) end++
    var start = offset
    while (true) {
        while (start > 0 && ident(text[start - 1])) start--
        if (start >= 2 && text[start - 1] == '.' && ident(text[start - 2])) start -= 1 else break
    }
    if (text[start].isDigit()) return null
    if (start > 0 && text[start - 1] == '.' && !(start > 1 && text[start - 2] == '.')) return null
    return TextRange(start, end)
}

/** The program as the IDE sees it: ant dap's output, and its end. */
class AntDapProcessHandler(private val onDestroy: () -> Unit, private val onDetach: () -> Unit) : ProcessHandler() {
    override fun destroyProcessImpl() = onDestroy()
    override fun detachProcessImpl() = onDetach()
    override fun detachIsDefault() = false
    override fun getProcessInput(): OutputStream? = null

    fun finish(code: Int) {
        if (!isProcessTerminated) notifyProcessTerminated(code)
    }
}
