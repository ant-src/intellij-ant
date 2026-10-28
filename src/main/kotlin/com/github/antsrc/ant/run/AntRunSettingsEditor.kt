package com.github.antsrc.ant.run

import com.github.antsrc.ant.AntFileType
import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.execution.ui.CommonParameterFragments
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.RawCommandLineEditor
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

class AntRunSettingsEditor(project: Project) : SettingsEditor<AntRunConfiguration>() {
    private val file = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileDescriptor(AntFileType).withTitle("Ant File"))
    }
    private val arguments = RawCommandLineEditor()
    private val workingDirectory = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Working Directory"))
    }
    private val environment = EnvironmentVariablesComponent()

    override fun resetEditorFrom(configuration: AntRunConfiguration) {
        file.text = configuration.file
        arguments.text = configuration.arguments
        workingDirectory.text = configuration.workingDirectory
        environment.envData = configuration.environment
    }

    override fun applyEditorTo(configuration: AntRunConfiguration) {
        configuration.file = file.text.trim()
        configuration.arguments = arguments.text
        configuration.workingDirectory = workingDirectory.text.trim()
        configuration.environment = environment.envData
    }

    override fun createEditor(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Ant file:", file)
        .addLabeledComponent("Program arguments:", arguments)
        .addLabeledComponent("Working directory:", workingDirectory)
        .addComponent(environment)
        .panel
}
