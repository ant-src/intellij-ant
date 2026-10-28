package com.github.antsrc.ant.highlight

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as D
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey as key

/** The text attributes of Ant code: the lexer's, and the semantic ones the language server colors. */
object AntColors {
    @JvmField val KEYWORD = key("ANT_KEYWORD", D.KEYWORD)
    @JvmField val RESERVED_WORD = key("ANT_RESERVED_WORD", HighlighterColors.BAD_CHARACTER)
    @JvmField val IDENTIFIER = key("ANT_IDENTIFIER", D.IDENTIFIER)
    @JvmField val NUMBER = key("ANT_NUMBER", D.NUMBER)
    @JvmField val STRING = key("ANT_STRING", D.STRING)
    @JvmField val VALID_ESCAPE = key("ANT_VALID_ESCAPE", D.VALID_STRING_ESCAPE)
    @JvmField val INVALID_ESCAPE = key("ANT_INVALID_ESCAPE", D.INVALID_STRING_ESCAPE)
    @JvmField val INTERPOLATION = key("ANT_INTERPOLATION", D.VALID_STRING_ESCAPE)
    @JvmField val REGEX = key("ANT_REGEX", D.STRING)
    @JvmField val LINE_COMMENT = key("ANT_LINE_COMMENT", D.LINE_COMMENT)
    @JvmField val BLOCK_COMMENT = key("ANT_BLOCK_COMMENT", D.BLOCK_COMMENT)
    @JvmField val DOC_COMMENT = key("ANT_DOC_COMMENT", D.DOC_COMMENT)
    @JvmField val OPERATOR = key("ANT_OPERATOR", D.OPERATION_SIGN)
    @JvmField val PIPE = key("ANT_PIPE", D.OPERATION_SIGN)
    @JvmField val BRACES = key("ANT_BRACES", D.BRACES)
    @JvmField val PARENTHESES = key("ANT_PARENTHESES", D.PARENTHESES)
    @JvmField val BRACKETS = key("ANT_BRACKETS", D.BRACKETS)
    @JvmField val COMMA = key("ANT_COMMA", D.COMMA)
    @JvmField val DOT = key("ANT_DOT", D.DOT)
    @JvmField val SEMICOLON = key("ANT_SEMICOLON", D.SEMICOLON)
    @JvmField val BAD_CHARACTER = key("ANT_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)

    @JvmField val MARKUP_TAG = key("ANT_MARKUP_TAG", D.MARKUP_TAG)
    @JvmField val MARKUP_ATTRIBUTE = key("ANT_MARKUP_ATTRIBUTE", D.MARKUP_ATTRIBUTE)
    @JvmField val MARKUP_ATTRIBUTE_VALUE = key("ANT_MARKUP_ATTRIBUTE_VALUE", D.STRING)
    @JvmField val MARKUP_TEXT = key("ANT_MARKUP_TEXT", HighlighterColors.TEXT)
    @JvmField val MARKUP_ENTITY = key("ANT_MARKUP_ENTITY", D.MARKUP_ENTITY)
    @JvmField val MARKUP_BRACES = key("ANT_MARKUP_BRACES", D.BRACES)

    // Semantic tokens from the language server (lsp.md section 4).
    @JvmField val NAMESPACE = key("ANT_NAMESPACE", D.CLASS_REFERENCE)
    @JvmField val TYPE = key("ANT_TYPE", D.CLASS_NAME)
    @JvmField val TYPE_PARAMETER = key("ANT_TYPE_PARAMETER", D.CLASS_REFERENCE)
    @JvmField val FUNCTION_DECLARATION = key("ANT_FUNCTION_DECLARATION", D.FUNCTION_DECLARATION)
    @JvmField val FUNCTION_CALL = key("ANT_FUNCTION_CALL", D.FUNCTION_CALL)
    @JvmField val PARAMETER = key("ANT_PARAMETER", D.PARAMETER)
    @JvmField val VARIABLE = key("ANT_VARIABLE", D.LOCAL_VARIABLE)
    @JvmField val CONSTANT = key("ANT_CONSTANT", D.CONSTANT)
    @JvmField val FIELD = key("ANT_FIELD", D.INSTANCE_FIELD)
    @JvmField val PRELUDE = key("ANT_PRELUDE", D.PREDEFINED_SYMBOL)
}
