package com.github.antsrc.ant.lexer

import com.github.antsrc.ant.AntLanguage
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

class AntTokenType(debugName: String) : IElementType(debugName, AntLanguage) {
    override fun toString() = "Ant:" + super.toString()
}

/** The tokens [AntLexer] produces (ant-lang.md sections 2 and 5). */
object AntTokenTypes {
    @JvmField val WHITE_SPACE: IElementType = TokenType.WHITE_SPACE
    @JvmField val BAD_CHARACTER: IElementType = TokenType.BAD_CHARACTER

    @JvmField val SHEBANG = AntTokenType("SHEBANG")
    @JvmField val LINE_COMMENT = AntTokenType("LINE_COMMENT")
    @JvmField val MODULE_DOC_COMMENT = AntTokenType("MODULE_DOC_COMMENT")
    @JvmField val BLOCK_COMMENT = AntTokenType("BLOCK_COMMENT")

    @JvmField val KEYWORD = AntTokenType("KEYWORD")
    /** `true`, `false`, and `null`. */
    @JvmField val LITERAL_KEYWORD = AntTokenType("LITERAL_KEYWORD")
    @JvmField val RESERVED = AntTokenType("RESERVED")
    @JvmField val IDENTIFIER = AntTokenType("IDENTIFIER")

    @JvmField val INT = AntTokenType("INT")
    @JvmField val FLOAT = AntTokenType("FLOAT")
    /** A string without an interpolation, quotes included. */
    @JvmField val STRING = AntTokenType("STRING")
    /** The pieces of a string with interpolations: its opening quote, its text, and its closing quote. */
    @JvmField val STRING_START = AntTokenType("STRING_START")
    @JvmField val STRING_TEXT = AntTokenType("STRING_TEXT")
    @JvmField val STRING_END = AntTokenType("STRING_END")
    @JvmField val INTERPOLATION_START = AntTokenType("INTERPOLATION_START")
    @JvmField val INTERPOLATION_END = AntTokenType("INTERPOLATION_END")
    /** A raw string between backticks, which has no escapes and no interpolations. */
    @JvmField val RAW_STRING = AntTokenType("RAW_STRING")
    @JvmField val REGEX = AntTokenType("REGEX")
    /** Pieces of a string or its text, from [AntEscapeLexer]. */
    @JvmField val VALID_ESCAPE = AntTokenType("VALID_ESCAPE")
    @JvmField val INVALID_ESCAPE = AntTokenType("INVALID_ESCAPE")

    @JvmField val LBRACE = AntTokenType("LBRACE")
    @JvmField val RBRACE = AntTokenType("RBRACE")
    @JvmField val LPAREN = AntTokenType("LPAREN")
    @JvmField val RPAREN = AntTokenType("RPAREN")
    @JvmField val LBRACKET = AntTokenType("LBRACKET")
    @JvmField val RBRACKET = AntTokenType("RBRACKET")
    @JvmField val COMMA = AntTokenType("COMMA")
    @JvmField val DOT = AntTokenType("DOT")
    @JvmField val QDOT = AntTokenType("QDOT")
    @JvmField val COLON = AntTokenType("COLON")
    @JvmField val SEMICOLON = AntTokenType("SEMICOLON")
    @JvmField val PIPE = AntTokenType("PIPE")
    @JvmField val ELLIPSIS = AntTokenType("ELLIPSIS")
    @JvmField val OPERATOR = AntTokenType("OPERATOR")

    @JvmField val TAG_OPEN = AntTokenType("TAG_OPEN")
    @JvmField val END_TAG_OPEN = AntTokenType("END_TAG_OPEN")
    @JvmField val TAG_CLOSE = AntTokenType("TAG_CLOSE")
    @JvmField val TAG_SELF_CLOSE = AntTokenType("TAG_SELF_CLOSE")
    @JvmField val TAG_NAME = AntTokenType("TAG_NAME")
    @JvmField val ATTR_NAME = AntTokenType("ATTR_NAME")
    @JvmField val ATTR_EQ = AntTokenType("ATTR_EQ")
    @JvmField val ATTR_VALUE = AntTokenType("ATTR_VALUE")
    @JvmField val MARKUP_TEXT = AntTokenType("MARKUP_TEXT")
    @JvmField val MARKUP_ENTITY = AntTokenType("MARKUP_ENTITY")
    @JvmField val MARKUP_EXPR_START = AntTokenType("MARKUP_EXPR_START")
    @JvmField val MARKUP_EXPR_END = AntTokenType("MARKUP_EXPR_END")
    @JvmField val RAW_TEXT = AntTokenType("RAW_TEXT")

    @JvmField val COMMENTS = TokenSet.create(SHEBANG, LINE_COMMENT, MODULE_DOC_COMMENT, BLOCK_COMMENT)
    @JvmField val STRINGS = TokenSet.create(STRING, STRING_START, STRING_TEXT, STRING_END, RAW_STRING, REGEX, ATTR_VALUE)
    @JvmField val OPENERS = TokenSet.create(LBRACE, LPAREN, LBRACKET, INTERPOLATION_START, MARKUP_EXPR_START, TAG_OPEN)
    @JvmField val CLOSERS = TokenSet.create(RBRACE, RPAREN, RBRACKET, INTERPOLATION_END, MARKUP_EXPR_END, END_TAG_OPEN)

    val KEYWORDS = setOf(
        "break", "continue", "defer", "else", "export", "false", "for", "func", "go", "if", "import",
        "in", "is", "null", "return", "select", "true", "try", "type", "var", "void", "while",
    )
    val RESERVED_WORDS = setOf(
        "as", "async", "await", "catch", "class", "const", "enum", "finally", "function", "interface",
        "let", "match", "switch", "this", "throw", "undefined",
    )
    val LITERAL_KEYWORDS = setOf("true", "false", "null")
}
