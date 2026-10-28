package com.github.antsrc.ant.run

import com.github.antsrc.ant.psi.AntFile
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement

/** Run 'file.ant' from the editor or the project view. */
class AntRunConfigurationProducer : LazyRunConfigurationProducer<AntRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(AntRunConfigurationType::class.java).factory

    override fun setupConfigurationFromContext(configuration: AntRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
        val file = context.psiLocation?.containingFile as? AntFile ?: return false
        val virtualFile = file.virtualFile?.takeIf { it.isInLocalFileSystem } ?: return false
        configuration.file = virtualFile.path
        val root = ProjectFileIndex.getInstance(context.project).getContentRootForFile(virtualFile)
        configuration.workingDirectory = root?.path ?: virtualFile.parent.path
        configuration.name = virtualFile.name
        sourceElement.set(file)
        return true
    }

    override fun isConfigurationFromContext(configuration: AntRunConfiguration, context: ConfigurationContext): Boolean {
        val file = context.psiLocation?.containingFile as? AntFile ?: return false
        return configuration.file == file.virtualFile?.path
    }
}
