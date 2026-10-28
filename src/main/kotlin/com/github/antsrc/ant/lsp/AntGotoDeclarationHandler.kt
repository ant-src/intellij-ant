package com.github.antsrc.ant.lsp

import com.github.antsrc.ant.lexer.AntTokenTypes
import com.github.antsrc.ant.psi.AntFile
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement

/**
 * Go to Declaration from a name or an import path, asked of the server. The IDE's own LSP client
 * answers only while a navigation action runs, so Cmd+hover showed no link; this handler answers
 * the hover too. At a declaration it answers nothing, so that the IDE shows the usages instead
 * (see [AntDeclarationProvider]).
 */
class AntGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val leaf = sourceElement as? LeafPsiElement ?: return null
        val file = leaf.containingFile as? AntFile ?: return null
        if (leaf.elementType != AntTokenTypes.IDENTIFIER && leaf.elementType != AntTokenTypes.STRING) return null
        val virtualFile = file.virtualFile ?: return null
        val targets = AntLspRequests.definitions(file.project, virtualFile, editor.document, offset)
            .filterNot { it.file == file && it.range.contains(leaf.textRange) }
        if (targets.isEmpty()) return null
        return targets.map { it.element }.toTypedArray()
    }
}
