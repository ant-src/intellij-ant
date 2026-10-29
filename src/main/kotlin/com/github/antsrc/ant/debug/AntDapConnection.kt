package com.github.antsrc.ant.debug

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.logger
import org.eclipse.lsp4j.debug.ExitedEventArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.OutputEventArguments
import org.eclipse.lsp4j.debug.StoppedEventArguments
import org.eclipse.lsp4j.debug.TerminatedEventArguments
import org.eclipse.lsp4j.debug.ThreadEventArguments
import org.eclipse.lsp4j.debug.launch.DSPLauncher
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A running `ant dap` (spec/dap.md) and the protocol to it. The events arrive on the
 * connection's own thread.
 */
class AntDapConnection(command: GeneralCommandLine, private val events: Events) : AutoCloseable {
    /** What the adapter reports. */
    interface Events {
        fun initialized() {}
        fun stopped(e: StoppedEventArguments) {}
        fun output(category: String, text: String) {}
        fun exited(code: Int) {}
        fun terminated() {}
        fun thread(e: ThreadEventArguments) {}
    }

    private val process: Process = command.createProcess()
    val server: IDebugProtocolServer

    init {
        val client = object : IDebugProtocolClient {
            override fun initialized() = events.initialized()
            override fun stopped(args: StoppedEventArguments) = events.stopped(args)
            override fun output(args: OutputEventArguments) = events.output(args.category ?: "console", args.output)
            override fun exited(args: ExitedEventArguments) = events.exited(args.exitCode)
            override fun terminated(args: TerminatedEventArguments?) = events.terminated()
            override fun thread(args: ThreadEventArguments) = events.thread(args)
        }
        val launcher = DSPLauncher.createClientLauncher(client, process.inputStream, process.outputStream)
        server = launcher.remoteProxy
        launcher.startListening()
        // What ant dap logs, so that it never blocks on a full pipe.
        thread(isDaemon = true, name = "ant dap stderr") {
            process.errorStream.bufferedReader().forEachLine { LOG.info("ant dap: $it") }
        }
    }

    /** Initializes the session and launches the program with the given attributes. */
    fun launch(attributes: Map<String, Any>) {
        server.initialize(InitializeRequestArguments().apply {
            clientID = "intellij"
            adapterID = "ant"
            linesStartAt1 = true
            columnsStartAt1 = true
            pathFormat = "path"
        }).get(TIMEOUT, TimeUnit.SECONDS)
        server.launch(attributes).get(TIMEOUT, TimeUnit.SECONDS)
    }

    override fun close() {
        process.destroy()
    }

    companion object {
        private val LOG = logger<AntDapConnection>()
        const val TIMEOUT = 10L
    }
}
