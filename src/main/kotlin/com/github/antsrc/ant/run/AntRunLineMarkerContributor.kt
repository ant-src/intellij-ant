package com.github.antsrc.ant.run

import com.github.antsrc.ant.lexer.AntTokenTypes
import com.github.antsrc.ant.psi.AntFile
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.source.tree.LeafPsiElement

/** The run icon in the gutter, on the first token of an Ant file. */
class AntRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (element !is LeafPsiElement || element.containingFile !is AntFile) return null
        if (element.elementType in AntTokenTypes.COMMENTS) return null
        var prev = element.prevSibling
        while (prev != null) {
            if (prev !is PsiWhiteSpace && (prev !is LeafPsiElement || prev.elementType !in AntTokenTypes.COMMENTS)) return null
            prev = prev.prevSibling
        }
        return Info(AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(0)) { "Run with ant" }
    }
}
