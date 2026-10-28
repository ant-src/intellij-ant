package com.github.antsrc.ant.psi

import com.github.antsrc.ant.AntLanguage
import com.github.antsrc.ant.lexer.AntLexer
import com.github.antsrc.ant.lexer.AntTokenTypes
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/**
 * The PSI of an Ant file is its tokens, flat under the file: the language server knows the
 * structure, and the editor features that need a tree (brace matching, indentation, folding,
 * structure view) take it from the tokens or from the server.
 */
class AntParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?) = AntLexer()

    override fun createParser(project: Project?) = PsiParser { root: IElementType, builder: PsiBuilder ->
        val file = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        file.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType() = FILE
    override fun getCommentTokens(): TokenSet = AntTokenTypes.COMMENTS
    override fun getStringLiteralElements(): TokenSet = AntTokenTypes.STRINGS
    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = AntFile(viewProvider)

    companion object {
        @JvmField val FILE = IFileElementType(AntLanguage)
    }
}
