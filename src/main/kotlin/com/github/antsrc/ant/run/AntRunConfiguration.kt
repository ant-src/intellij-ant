package com.github.antsrc.ant.run

import com.github.antsrc.ant.settings.AntExecutable
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.filters.RegexpFilter
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import java.io.File

class AntRunConfigurationOptions : LocatableRunConfigurationOptions() {
    var file by string("")
    var arguments by string("")
    var workingDirectory by string("")
    var environment by map<String, String>()
    var passParentEnvironment by property(true)
}

/** Runs `ant run <file> [args...]` (ant-lang.md section 10). */
class AntRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<AntRunConfigurationOptions>(project, factory, name) {

    public override fun getOptions() = super.getOptions() as AntRunConfigurationOptions

    var file: String
        get() = options.file ?: ""
        set(value) { options.file = value }
    var arguments: String
        get() = options.arguments ?: ""
        set(value) { options.arguments = value }
    var workingDirectory: String
        get() = options.workingDirectory ?: ""
        set(value) { options.workingDirectory = value }
    var environment: EnvironmentVariablesData
        get() = EnvironmentVariablesData.create(options.environment, options.passParentEnvironment)
        set(value) {
            options.environment = value.envs.toMutableMap()
            options.passParentEnvironment = value.isPassParentEnvs
        }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = AntRunSettingsEditor(project)

    override fun checkConfiguration() {
        if (file.isBlank()) throw RuntimeConfigurationError("No Ant file to run")
        if (!File(file).isFile) throw RuntimeConfigurationError("The file does not exist: $file")
    }

    override fun suggestedName(): String? = file.takeIf { it.isNotBlank() }?.let { File(it).name }

    /** The command line as it will run, for the state and for tests. */
    @Throws(ExecutionException::class)
    fun commandLine(): GeneralCommandLine {
        val ant = AntExecutable.find() ?: throw ExecutionException(AntExecutable.NOT_FOUND)
        val cmd = GeneralCommandLine(ant.toString(), "run", file)
        cmd.addParameters(ParametersListUtil.parse(arguments))
        cmd.withWorkDirectory(workingDirectory())
        environment.configureCommandLine(cmd, true)
        return cmd
    }

    /** The command line of `ant dap`, which debugs the configuration (spec/dap.md). */
    @Throws(ExecutionException::class)
    fun debugCommandLine(): GeneralCommandLine {
        val ant = AntExecutable.find() ?: throw ExecutionException(AntExecutable.NOT_FOUND)
        return GeneralCommandLine(ant.toString(), "dap").withWorkDirectory(workingDirectory())
    }

    /** The attributes of ant dap's launch request (spec/dap.md section 2). */
    fun launchAttributes(): Map<String, Any> = mapOf(
        "program" to file,
        "args" to ParametersListUtil.parse(arguments),
        "cwd" to workingDirectory(),
        "env" to environment.envs,
    )

    private fun workingDirectory(): String = workingDirectory.ifBlank { File(file).parent ?: project.basePath ?: "." }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        return object : CommandLineState(environment) {
            init {
                // ant prints path:line:col for an error, which opens the file at the position.
                addConsoleFilters(RegexpFilter(project, "${RegexpFilter.FILE_PATH_MACROS}:${RegexpFilter.LINE_MACROS}:${RegexpFilter.COLUMN_MACROS}"))
            }

            @Throws(ExecutionException::class)
            override fun startProcess(): ProcessHandler {
                val handler = KillableColoredProcessHandler(commandLine())
                ProcessTerminatedListener.attach(handler)
                return handler
            }
        }
    }
}
