package com.github.antsrc.ant

import com.github.antsrc.ant.lexer.AntEscapeLexer
import com.github.antsrc.ant.lexer.AntLexer
import com.github.antsrc.ant.lexer.AntTokenTypes
import com.intellij.lexer.LayeredLexer
import com.intellij.lexer.Lexer
import com.intellij.psi.tree.IElementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class Tok(val type: IElementType, val text: String) {
    override fun toString() = "${type.toString().removePrefix("Ant:")}(${text.replace("\n", "\\n")})"
}

/** Tokenizes src with the highlighting lexer, with escapes split as the editor sees them; testFile lexes it as a `_test.ant` file. */
fun lex(src: String, escapes: Boolean = false, testFile: Boolean = false): List<Tok> {
    val lexer: Lexer = if (escapes) {
        LayeredLexer(AntLexer(testFile)).apply {
            registerSelfStoppingLayer(AntEscapeLexer(AntTokenTypes.STRING), arrayOf(AntTokenTypes.STRING), IElementType.EMPTY_ARRAY)
            registerSelfStoppingLayer(AntEscapeLexer(AntTokenTypes.STRING_TEXT), arrayOf(AntTokenTypes.STRING_TEXT), IElementType.EMPTY_ARRAY)
        }
    } else {
        AntLexer(testFile)
    }
    lexer.start(src)
    val out = ArrayList<Tok>()
    var expected = 0
    while (true) {
        val type = lexer.tokenType ?: break
        assertEquals("tokens are contiguous in ${src.take(60)}", expected, lexer.tokenStart)
        assertTrue("tokens are not empty in ${src.take(60)}", lexer.tokenEnd > lexer.tokenStart)
        out.add(Tok(type, src.substring(lexer.tokenStart, lexer.tokenEnd)))
        expected = lexer.tokenEnd
        lexer.advance()
    }
    assertEquals("tokens cover the source", src.length, expected)
    return out
}

/** The type of the first token with the given text, skipping white space. */
fun typeOf(src: String, text: String): IElementType =
    lex(src).firstOrNull { it.text == text }?.type ?: throw AssertionError("no token $text in ${lex(src)}")

/** The tokens that are not white space, as `TYPE(text)`. */
fun shape(src: String, testFile: Boolean = false): String = lex(src, testFile = testFile).filter { it.type != AntTokenTypes.WHITE_SPACE }.joinToString(" ")

/** Whether the lexer ends src with nothing open: at the top level with the next line starting fresh. */
fun endsBalanced(src: String, testFile: Boolean = false): Boolean {
    val lexer = AntLexer(testFile)
    lexer.start("$src\n;")
    var last: IElementType? = null
    while (lexer.tokenType != null) {
        last = lexer.tokenType
        lexer.advance()
    }
    return last == AntTokenTypes.SEMICOLON
}
