package com.github.antsrc.ant.run

import com.github.antsrc.ant.AntFileType
import com.github.antsrc.ant.psi.AntFile
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement

/**
 * Run 'file.ant' from the editor or the project view. In a test file, a test's `test` or a place
 * inside its body runs that test, and anywhere else every test of the file. A library, neither a
 * script nor a test file, has nothing to run (AntScripts).
 */
class AntRunConfigurationProducer : LazyRunConfigurationProducer<AntRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(AntRunConfigurationType::class.java).factory

    override fun setupConfigurationFromContext(configuration: AntRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
        val location = context.psiLocation ?: return false
        val file = location.containingFile as? AntFile ?: return false
        if (!AntFileType.isTestFile(file.name) && !AntScripts.isScript(file)) return false
        val virtualFile = file.virtualFile?.takeIf { it.isInLocalFileSystem } ?: return false
        val test = testAt(location)
        configure(configuration, context.project, virtualFile, test)
        sourceElement.set(test?.keyword ?: file)
        return true
    }

    override fun isConfigurationFromContext(configuration: AntRunConfiguration, context: ConfigurationContext): Boolean {
        val location = context.psiLocation ?: return false
        val file = location.containingFile as? AntFile ?: return false
        return configuration.file == file.virtualFile?.path && configuration.testPattern == (testAt(location)?.pattern ?: "")
    }

    /** The test at element: the one whose `test` it is, or the innermost whose body holds it. */
    private fun testAt(element: PsiElement): AntTest? {
        if (element is AntFile) return null
        return AntTests.at(element) ?: AntTests.around(element.containingFile, element.textRange.startOffset)
    }

    companion object {
        /** Sets configuration up to run file, or one test of it, from the file's content root. */
        fun configure(configuration: AntRunConfiguration, project: Project, file: VirtualFile, test: AntTest?) {
            configure(configuration, project, file, test?.pattern ?: "", test?.name)
        }

        fun configure(configuration: AntRunConfiguration, project: Project, file: VirtualFile, pattern: String, testName: String?) {
            configuration.file = file.path
            configuration.testPattern = pattern
            val root = ProjectFileIndex.getInstance(project).getContentRootForFile(file)
            configuration.workingDirectory = root?.path ?: file.parent.path
            configuration.name = if (testName != null) "${file.name}: $testName" else file.name
        }
    }
}
