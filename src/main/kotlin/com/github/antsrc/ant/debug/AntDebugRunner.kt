package com.github.antsrc.ant.debug

import com.github.antsrc.ant.run.AntRunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import java.lang.reflect.InvocationTargetException

/** Debugs an Ant run configuration through ant dap. */
class AntDebugRunner : GenericProgramRunner<RunnerSettings>() {
    override fun getRunnerId() = "AntDebugRunner"

    override fun canRun(executorId: String, profile: RunProfile) =
        executorId == DefaultDebugExecutor.EXECUTOR_ID && profile is AntRunConfiguration

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor {
        FileDocumentManager.getInstance().saveAllDocuments()
        val configuration = environment.runProfile as AntRunConfiguration
        val starter = object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess = AntDebugProcess(session, configuration)
        }
        val manager = XDebuggerManager.getInstance(environment.project)
        return startWithBuilder(manager, starter, environment)
            ?: @Suppress("DEPRECATION") manager.startSession(environment, starter).runContentDescriptor
    }

    /**
     * Starts the session through XDebuggerManager.newSessionBuilder, where the IDE has it (2026.1
     * on), and returns its tab. XDebugSession.getRunContentDescriptor logs an error in the split
     * debugger there, while 2025.3 has only startSession, so the builder is found reflectively.
     */
    private fun startWithBuilder(manager: XDebuggerManager, starter: XDebugProcessStarter, environment: ExecutionEnvironment): RunContentDescriptor? {
        val newBuilder = manager.javaClass.methods.firstOrNull { it.name == "newSessionBuilder" && it.parameterCount == 1 } ?: return null
        try {
            val builderType = newBuilder.returnType
            var builder = newBuilder.invoke(manager, starter)
            builder = builderType.getMethod("environment", ExecutionEnvironment::class.java).invoke(builder, environment)
            val start = builderType.getMethod("startSession")
            val result = start.invoke(builder)
            return start.returnType.getMethod("getRunContentDescriptor").invoke(result) as RunContentDescriptor
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}
