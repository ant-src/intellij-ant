package com.github.antsrc.ant.lexer

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/**
 * Splits a string literal or a run of template text into its text and its escapes, so that the
 * highlighter colors a valid escape and marks an invalid one (section 2, Literals). The escapes
 * are `\n`, `\t`, `\r`, `\\`, `\"`, `` \` ``, `\$`, and `\u{...}` naming a Unicode scalar value.
 */
class AntEscapeLexer(private val text: IElementType) : LexerBase() {
    private var buffer: CharSequence = ""
    private var end = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        end = endOffset
        tokenEnd = startOffset
        advance()
    }

    override fun getState() = 0
    override fun getTokenType() = tokenType
    override fun getTokenStart() = tokenStart
    override fun getTokenEnd() = tokenEnd
    override fun getBufferSequence() = buffer
    override fun getBufferEnd() = end

    override fun advance() {
        tokenStart = tokenEnd
        if (tokenStart >= end) {
            tokenType = null
            return
        }
        if (buffer[tokenStart] != '\\') {
            var i = tokenStart + 1
            while (i < end && buffer[i] != '\\') i++
            tokenEnd = i
            tokenType = text
            return
        }
        if (tokenStart + 1 >= end) {
            tokenEnd = end
            tokenType = AntTokenTypes.INVALID_ESCAPE
            return
        }
        when (val c = buffer[tokenStart + 1]) {
            'n', 't', 'r', '\\', '"', '`', '$' -> {
                tokenEnd = tokenStart + 2
                tokenType = AntTokenTypes.VALID_ESCAPE
            }
            'u' -> {
                var i = tokenStart + 2
                var valid = false
                if (i < end && buffer[i] == '{') {
                    i++
                    val digits = i
                    while (i < end && isHex(buffer[i])) i++
                    if (i > digits && i < end && buffer[i] == '}') {
                        val v = buffer.subSequence(digits, i).toString().toLongOrNull(16) ?: -1
                        valid = v in 0..0x10FFFF && v !in 0xD800..0xDFFF
                        i++
                    }
                }
                tokenEnd = i
                tokenType = if (valid) AntTokenTypes.VALID_ESCAPE else AntTokenTypes.INVALID_ESCAPE
            }
            else -> {
                tokenEnd = tokenStart + (if (Character.isHighSurrogate(c) && tokenStart + 2 < end) 3 else 2)
                tokenType = AntTokenTypes.INVALID_ESCAPE
            }
        }
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
}
