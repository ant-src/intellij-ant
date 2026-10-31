package com.github.antsrc.ant.lsp

import com.github.antsrc.ant.run.AntRunConfiguration
import com.github.antsrc.ant.run.AntRunConfigurationProducer
import com.github.antsrc.ant.run.AntRunConfigurationType
import com.github.antsrc.ant.run.AntTests
import com.google.gson.JsonPrimitive
import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.customization.LspCommandsSupport
import org.eclipse.lsp4j.Command

/**
 * The commands of `ant lsp`'s code lenses, which the client carries out (lsp.md section 4, Code
 * lenses): `ant.test.run` runs a test with `ant test -run`, and `ant.test.debug` debugs it under
 * `ant dap`, both through an Ant run configuration for the test. 2025.3's LSP client shows no code
 * lenses, so there the gutter icons of AntRunLineMarkerContributor are the only way; 2026.2's shows
 * them and passes a click on one here.
 */
class AntCommandsSupport : LspCommandsSupport() {
    override fun executeCommand(server: LspServer, contextFile: VirtualFile, command: Command) {
        val executor = when (command.command) {
            RUN_TEST -> DefaultRunExecutor.getRunExecutorInstance()
            DEBUG_TEST -> DefaultDebugExecutor.getDebugExecutorInstance()
            else -> return super.executeCommand(server, contextFile, command)
        }
        // The arguments are the document's URI, which is contextFile's, and the -run pattern.
        val pattern = command.arguments?.getOrNull(1)?.let { (it as? JsonPrimitive)?.asString ?: it as? String } ?: return
        val project = server.project
        ApplicationManager.getApplication().invokeLater({ runTest(project, contextFile, pattern, executor) }, project.disposed)
    }

    companion object {
        const val RUN_TEST = "ant.test.run"
        const val DEBUG_TEST = "ant.test.debug"

        /** Runs the tests of file that pattern selects with executor, through the run configuration for them, made temporary when there is none. */
        fun runTest(project: Project, file: VirtualFile, pattern: String, executor: Executor) {
            val runManager = RunManager.getInstance(project)
            val type = ConfigurationTypeUtil.findConfigurationType(AntRunConfigurationType::class.java)
            val settings = runManager.getConfigurationSettingsList(type).firstOrNull {
                val c = it.configuration as AntRunConfiguration
                c.file == file.path && c.testPattern == pattern
            } ?: runManager.createConfiguration("", type.factory).also {
                val name = ReadAction.compute<String?, RuntimeException> {
                    PsiManager.getInstance(project).findFile(file)?.let { psi -> AntTests.of(psi).firstOrNull { t -> t.pattern == pattern }?.name }
                }
                AntRunConfigurationProducer.configure(it.configuration as AntRunConfiguration, project, file, pattern, name ?: pattern)
                runManager.setTemporaryConfiguration(it)
            }
            runManager.selectedConfiguration = settings
            ProgramRunnerUtil.executeConfiguration(settings, executor)
        }
    }
}
