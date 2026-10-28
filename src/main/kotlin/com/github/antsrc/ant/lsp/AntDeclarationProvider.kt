package com.github.antsrc.ant.lsp

import com.github.antsrc.ant.AntIcons
import com.github.antsrc.ant.lexer.AntTokenTypes
import com.github.antsrc.ant.psi.AntFile
import com.intellij.find.usages.api.PsiUsage
import com.intellij.find.usages.api.SearchTarget
import com.intellij.find.usages.api.Usage
import com.intellij.find.usages.api.UsageHandler
import com.intellij.find.usages.api.UsageSearchParameters
import com.intellij.find.usages.api.UsageSearcher
import com.intellij.find.usages.symbol.SearchTargetSymbol
import com.intellij.model.Pointer
import com.intellij.model.psi.PsiSymbolDeclaration
import com.intellij.model.psi.PsiSymbolDeclarationProvider
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement

/**
 * A name the server says is declared where it stands is a declaration: Cmd+click on it shows the
 * usages, and Find Usages finds them through the server (lsp.md section 4, References). The IDE's
 * own LSP client knows no declarations, so it shows none.
 */
class AntDeclarationProvider : PsiSymbolDeclarationProvider {
    override fun getDeclarations(element: PsiElement, offsetInElement: Int): Collection<PsiSymbolDeclaration> {
        val leaf = element as? LeafPsiElement ?: return emptyList()
        if (leaf.elementType != AntTokenTypes.IDENTIFIER) return emptyList()
        val file = leaf.containingFile as? AntFile ?: return emptyList()
        val virtualFile = file.virtualFile ?: return emptyList()
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return emptyList()
        val here = AntLspRequests.definitions(file.project, virtualFile, document, leaf.textRange.startOffset)
            .any { it.file == file && it.range.contains(leaf.textRange) }
        if (!here) return emptyList()
        return listOf(AntDeclaration(leaf, AntSymbol(file.project, virtualFile, leaf.textRange, leaf.text)))
    }
}

class AntDeclaration(private val leaf: PsiElement, private val symbol: AntSymbol) : PsiSymbolDeclaration {
    override fun getDeclaringElement(): PsiElement = leaf
    override fun getRangeInDeclaringElement(): TextRange = TextRange(0, leaf.textLength)
    override fun getSymbol(): AntSymbol = symbol
}

/** A declared name, by its file and range; the server finds its usages. */
data class AntSymbol(val project: Project, val file: VirtualFile, val range: TextRange, val name: String) : SearchTargetSymbol, SearchTarget {
    override fun createPointer(): Pointer<AntSymbol> = Pointer.hardPointer(this)
    override val searchTarget: SearchTarget get() = this
    override val usageHandler: UsageHandler get() = UsageHandler.createEmptyUsageHandler(name)
    override fun presentation(): TargetPresentation =
        TargetPresentation.builder(name).icon(AntIcons.FILE).containerText(file.name).presentation()
}

class AntUsageSearcher : UsageSearcher {
    override fun collectImmediateResults(parameters: UsageSearchParameters): Collection<Usage> {
        val target = parameters.target as? AntSymbol ?: return emptyList()
        val document = FileDocumentManager.getInstance().getDocument(target.file) ?: return emptyList()
        return AntLspRequests.references(target.project, target.file, document, target.range.startOffset)
            .filter { use -> use.file.virtualFile?.let { parameters.searchScope.contains(it) } ?: false }
            .map { PsiUsage.textUsage(it.file, it.range) }
    }
}
