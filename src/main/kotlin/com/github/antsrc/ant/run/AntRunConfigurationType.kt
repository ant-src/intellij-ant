package com.github.antsrc.ant.run

import com.github.antsrc.ant.AntIcons
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project

class AntRunConfigurationType : ConfigurationType {
    override fun getDisplayName() = "Ant"
    override fun getConfigurationTypeDescription() = "Run an Ant file with ant run"
    override fun getIcon() = AntIcons.FILE
    override fun getId() = ID
    override fun getConfigurationFactories() = arrayOf(factory)

    val factory: ConfigurationFactory = object : ConfigurationFactory(this) {
        override fun getId() = ID
        override fun createTemplateConfiguration(project: Project): RunConfiguration = AntRunConfiguration(project, this, "Ant")
        override fun getOptionsClass(): Class<out BaseState> = AntRunConfigurationOptions::class.java
    }

    companion object {
        const val ID = "AntRunConfiguration"
    }
}
