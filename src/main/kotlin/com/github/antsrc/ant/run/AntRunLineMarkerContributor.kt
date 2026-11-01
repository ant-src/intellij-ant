package com.github.antsrc.ant.run

import com.github.antsrc.ant.AntFileType
import com.github.antsrc.ant.psi.AntFile
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.execution.TestStateStorage
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.source.tree.LeafPsiElement
import javax.swing.Icon

/**
 * The run icon in the gutter: on the first token of a script or a test file that is code, not a
 * comment, not white space, since a library has nothing to run (AntScripts); and in a test file on the `test` of each test (AntTests), which runs or debugs that
 * test alone, or every case of a subtest whose name interpolates, and shows how it last went.
 */
class AntRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (!isCode(element) || element.containingFile !is AntFile) return null
        AntTests.at(element)?.let { test ->
            return Info(stateIcon(element, test), ExecutorAction.getActions(0)) { "Run test '${test.name}'" }
        }
        var prev = element.prevSibling
        while (prev != null) {
            if (isCode(prev)) return null
            prev = prev.prevSibling
        }
        val file = element.containingFile as AntFile
        val tooltip = when {
            AntFileType.isTestFile(file.name) -> "Run the file's tests"
            AntScripts.isScript(file) -> "Run with ant"
            else -> return null
        }
        return Info(AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(0)) { tooltip }
    }

    /**
     * The icon of the test's last result: of the test, by its full name, or of a template's cases
     * together, the worst of them, since its full names are known only once they ran.
     */
    private fun stateIcon(element: PsiElement, test: AntTest): Icon {
        val path = element.containingFile.virtualFile?.path ?: return AllIcons.RunConfigurations.TestState.Run
        val project = element.project
        test.fullName?.let { return getTestStateIcon(AntTestLocator.url(path, it), project, false) }
        val storage = TestStateStorage.getInstance(project)
        val prefix = AntTestLocator.url(path, "")
        val worst = storage.keys.asSequence()
            .filter { it.startsWith(prefix) && test.fullNames.matches(it.substring(prefix.length)) }
            .mapNotNull { storage.getState(it) }
            .maxByOrNull { it.magnitude }
            ?: return AllIcons.RunConfigurations.TestState.Run
        return getTestStateIcon(worst, false)
    }

    private fun isCode(element: PsiElement) = element is LeafPsiElement && element !is PsiComment && element !is PsiWhiteSpace
}
