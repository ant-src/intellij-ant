package com.github.antsrc.ant.highlight

import com.github.antsrc.ant.lexer.AntEscapeLexer
import com.github.antsrc.ant.lexer.AntLexer
import com.github.antsrc.ant.lexer.AntTokenTypes as T
import com.intellij.lexer.LayeredLexer
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType

class AntSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer {
        val lexer = LayeredLexer(AntLexer())
        lexer.registerSelfStoppingLayer(AntEscapeLexer(T.STRING), arrayOf(T.STRING), IElementType.EMPTY_ARRAY)
        lexer.registerSelfStoppingLayer(AntEscapeLexer(T.TEMPLATE_TEXT), arrayOf(T.TEMPLATE_TEXT), IElementType.EMPTY_ARRAY)
        return lexer
    }

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = pack(KEYS[tokenType])

    companion object {
        private val KEYS: Map<IElementType, TextAttributesKey> = mapOf(
            T.KEYWORD to AntColors.KEYWORD,
            T.LITERAL_KEYWORD to AntColors.KEYWORD,
            T.RESERVED to AntColors.RESERVED_WORD,
            T.IDENTIFIER to AntColors.IDENTIFIER,
            T.INT to AntColors.NUMBER,
            T.FLOAT to AntColors.NUMBER,
            T.STRING to AntColors.STRING,
            T.TEMPLATE_START to AntColors.STRING,
            T.TEMPLATE_TEXT to AntColors.STRING,
            T.TEMPLATE_END to AntColors.STRING,
            T.INTERPOLATION_START to AntColors.INTERPOLATION,
            T.INTERPOLATION_END to AntColors.INTERPOLATION,
            T.VALID_ESCAPE to AntColors.VALID_ESCAPE,
            T.INVALID_ESCAPE to AntColors.INVALID_ESCAPE,
            T.REGEX to AntColors.REGEX,
            T.SHEBANG to AntColors.LINE_COMMENT,
            T.LINE_COMMENT to AntColors.LINE_COMMENT,
            T.MODULE_DOC_COMMENT to AntColors.DOC_COMMENT,
            T.BLOCK_COMMENT to AntColors.BLOCK_COMMENT,
            T.OPERATOR to AntColors.OPERATOR,
            T.ELLIPSIS to AntColors.OPERATOR,
            T.COLON to AntColors.OPERATOR,
            T.PIPE to AntColors.PIPE,
            T.LBRACE to AntColors.BRACES,
            T.RBRACE to AntColors.BRACES,
            T.LPAREN to AntColors.PARENTHESES,
            T.RPAREN to AntColors.PARENTHESES,
            T.LBRACKET to AntColors.BRACKETS,
            T.RBRACKET to AntColors.BRACKETS,
            T.COMMA to AntColors.COMMA,
            T.DOT to AntColors.DOT,
            T.QDOT to AntColors.DOT,
            T.SEMICOLON to AntColors.SEMICOLON,
            T.BAD_CHARACTER to AntColors.BAD_CHARACTER,
            T.TAG_OPEN to AntColors.MARKUP_TAG,
            T.END_TAG_OPEN to AntColors.MARKUP_TAG,
            T.TAG_CLOSE to AntColors.MARKUP_TAG,
            T.TAG_SELF_CLOSE to AntColors.MARKUP_TAG,
            T.TAG_NAME to AntColors.MARKUP_TAG,
            T.ATTR_NAME to AntColors.MARKUP_ATTRIBUTE,
            T.ATTR_EQ to AntColors.MARKUP_ATTRIBUTE,
            T.ATTR_VALUE to AntColors.MARKUP_ATTRIBUTE_VALUE,
            T.MARKUP_TEXT to AntColors.MARKUP_TEXT,
            T.RAW_TEXT to AntColors.MARKUP_TEXT,
            T.MARKUP_ENTITY to AntColors.MARKUP_ENTITY,
            T.MARKUP_EXPR_START to AntColors.MARKUP_BRACES,
            T.MARKUP_EXPR_END to AntColors.MARKUP_BRACES,
        )
    }
}

class AntSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?) = AntSyntaxHighlighter()
}
