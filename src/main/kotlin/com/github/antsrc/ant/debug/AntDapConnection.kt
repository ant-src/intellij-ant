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
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * A running `ant dap` (spec/dap.md) and the protocol to it. The events arrive on the
 * connection's own thread; the adapter's own end, which a crash or an `ant` without `dap` brings
 * before any event, on another.
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

        /** ant dap itself ended, with its exit status (spec/dap.md section 1) and the last lines it logged. */
        fun adapterExited(code: Int, log: List<String>) {}
    }

    /** ant dap ended while a request waited for its answer. */
    class Exited(code: Int) : IOException("ant dap exited with status $code")

    private val process: Process = command.createProcess()
    private val exit: CompletableFuture<Process> = process.onExit()
    private val log = ArrayDeque<String>() // the last LOG_LINES lines of standard error
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
        // What ant dap logs, so that it never blocks on a full pipe, and the end of it is at hand when it exits.
        val stderr = thread(isDaemon = true, name = "ant dap stderr") {
            process.errorStream.bufferedReader().forEachLine { line ->
                LOG.info("ant dap: $line")
                synchronized(log) {
                    log.addLast(line)
                    if (log.size > LOG_LINES) log.removeFirst()
                }
            }
        }
        exit.thenAccept { p ->
            stderr.join(1000)
            events.adapterExited(p.exitValue(), synchronized(log) { log.toList() })
        }
    }

    /** Initializes the session and launches the program with the given attributes. */
    fun launch(attributes: Map<String, Any>) {
        await(server.initialize(InitializeRequestArguments().apply {
            clientID = "intellij"
            adapterID = "ant"
            linesStartAt1 = true
            columnsStartAt1 = true
            pathFormat = "path"
        }))
        await(server.launch(attributes))
    }

    /**
     * A request's answer, within seconds of asking. A request that ant dap exits without
     * answering, or does not answer in time, fails with what happened instead of waiting on:
     * the protocol library leaves the answer pending for good when the adapter is gone.
     */
    fun <T> await(response: CompletableFuture<T>, seconds: Long = TIMEOUT): T {
        try {
            CompletableFuture.anyOf(response, exit).get(seconds, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            // The response failed; response.get() below says how.
        } catch (e: TimeoutException) {
            throw TimeoutException("ant dap did not answer within $seconds seconds")
        }
        if (!response.isDone) throw Exited(process.exitValue())
        return response.get()
    }

    override fun close() {
        process.destroy()
    }

    companion object {
        private val LOG = logger<AntDapConnection>()
        const val TIMEOUT = 10L
        private const val LOG_LINES = 20
    }
}
