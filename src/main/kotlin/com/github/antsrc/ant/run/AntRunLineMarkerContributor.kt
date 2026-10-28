package com.github.antsrc.ant.run

import com.github.antsrc.ant.psi.AntFile
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.source.tree.LeafPsiElement

/** The run icon in the gutter, on the first token of an Ant file that is code: not a comment, not white space. */
class AntRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (!isCode(element) || element.containingFile !is AntFile) return null
        var prev = element.prevSibling
        while (prev != null) {
            if (isCode(prev)) return null
            prev = prev.prevSibling
        }
        return Info(AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(0)) { "Run with ant" }
    }

    private fun isCode(element: PsiElement) = element is LeafPsiElement && element !is PsiComment && element !is PsiWhiteSpace
}
