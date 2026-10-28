package com.github.antsrc.ant.lsp

import com.github.antsrc.ant.highlight.AntColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport
import org.eclipse.lsp4j.SemanticTokenModifiers
import org.eclipse.lsp4j.SemanticTokenTypes

/** The colors of the server's semantic tokens (lsp.md section 4), including its own `element` and `attribute`. */
class AntSemanticTokensSupport : LspSemanticTokensSupport() {
    override val tokenTypes: List<String> = super.tokenTypes + listOf(ELEMENT, ATTRIBUTE)

    override fun getTextAttributesKey(tokenType: String, tokenModifiers: List<String>): TextAttributesKey? {
        val declaration = SemanticTokenModifiers.Declaration in tokenModifiers || SemanticTokenModifiers.Definition in tokenModifiers
        val prelude = SemanticTokenModifiers.DefaultLibrary in tokenModifiers
        return when (tokenType) {
            ELEMENT -> AntColors.MARKUP_TAG
            ATTRIBUTE -> AntColors.MARKUP_ATTRIBUTE
            SemanticTokenTypes.Namespace -> AntColors.NAMESPACE
            SemanticTokenTypes.Type, SemanticTokenTypes.Class, SemanticTokenTypes.Struct, SemanticTokenTypes.Enum, SemanticTokenTypes.Interface ->
                if (prelude) AntColors.PRELUDE else AntColors.TYPE
            SemanticTokenTypes.TypeParameter -> AntColors.TYPE_PARAMETER
            SemanticTokenTypes.Function, SemanticTokenTypes.Method -> when {
                prelude -> AntColors.PRELUDE
                declaration -> AntColors.FUNCTION_DECLARATION
                else -> AntColors.FUNCTION_CALL
            }
            SemanticTokenTypes.Parameter -> AntColors.PARAMETER
            SemanticTokenTypes.Variable -> when {
                prelude -> AntColors.PRELUDE
                "global" in tokenModifiers || SemanticTokenModifiers.Static in tokenModifiers -> AntColors.GLOBAL_VARIABLE
                else -> AntColors.VARIABLE
            }
            SemanticTokenTypes.Property -> AntColors.FIELD
            else -> super.getTextAttributesKey(tokenType, tokenModifiers)
        }
    }

    companion object {
        const val ELEMENT = "element"
        const val ATTRIBUTE = "attribute"
    }
}
