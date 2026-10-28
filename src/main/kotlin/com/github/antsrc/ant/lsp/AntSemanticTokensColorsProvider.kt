package com.github.antsrc.ant.lsp

import com.github.antsrc.ant.highlight.AntColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.features.semanticTokens.DefaultSemanticTokensColorsProvider
import org.eclipse.lsp4j.SemanticTokenModifiers
import org.eclipse.lsp4j.SemanticTokenTypes

/** The colors of the server's semantic tokens (lsp.md section 4), including its own `element` and `attribute`. */
class AntSemanticTokensColorsProvider : DefaultSemanticTokensColorsProvider() {
    override fun getTextAttributesKey(tokenType: String, tokenModifiers: List<String>, file: PsiFile): TextAttributesKey? {
        val declaration = SemanticTokenModifiers.Declaration in tokenModifiers || SemanticTokenModifiers.Definition in tokenModifiers
        return when (tokenType) {
            "element" -> AntColors.MARKUP_TAG
            "attribute" -> AntColors.MARKUP_ATTRIBUTE
            SemanticTokenTypes.Namespace -> AntColors.NAMESPACE
            SemanticTokenTypes.Type, SemanticTokenTypes.Class, SemanticTokenTypes.Struct, SemanticTokenTypes.Enum, SemanticTokenTypes.Interface ->
                if (SemanticTokenModifiers.DefaultLibrary in tokenModifiers) AntColors.PRELUDE else AntColors.TYPE
            SemanticTokenTypes.TypeParameter -> AntColors.TYPE_PARAMETER
            SemanticTokenTypes.Function, SemanticTokenTypes.Method -> when {
                SemanticTokenModifiers.DefaultLibrary in tokenModifiers -> AntColors.PRELUDE
                declaration -> AntColors.FUNCTION_DECLARATION
                else -> AntColors.FUNCTION_CALL
            }
            SemanticTokenTypes.Parameter -> AntColors.PARAMETER
            SemanticTokenTypes.Variable -> when {
                SemanticTokenModifiers.DefaultLibrary in tokenModifiers -> AntColors.PRELUDE
                "global" in tokenModifiers || SemanticTokenModifiers.Static in tokenModifiers -> AntColors.GLOBAL_VARIABLE
                else -> AntColors.VARIABLE
            }
            SemanticTokenTypes.Property -> AntColors.FIELD
            else -> super.getTextAttributesKey(tokenType, tokenModifiers, file)
        }
    }
}
