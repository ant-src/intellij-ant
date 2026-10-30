package com.github.antsrc.ant.lexer

import com.github.antsrc.ant.lexer.AntTokenTypes.ATTR_EQ
import com.github.antsrc.ant.lexer.AntTokenTypes.ATTR_NAME
import com.github.antsrc.ant.lexer.AntTokenTypes.ATTR_VALUE
import com.github.antsrc.ant.lexer.AntTokenTypes.BAD_CHARACTER
import com.github.antsrc.ant.lexer.AntTokenTypes.BLOCK_COMMENT
import com.github.antsrc.ant.lexer.AntTokenTypes.COLON
import com.github.antsrc.ant.lexer.AntTokenTypes.COMMA
import com.github.antsrc.ant.lexer.AntTokenTypes.DOT
import com.github.antsrc.ant.lexer.AntTokenTypes.ELLIPSIS
import com.github.antsrc.ant.lexer.AntTokenTypes.END_TAG_OPEN
import com.github.antsrc.ant.lexer.AntTokenTypes.FLOAT
import com.github.antsrc.ant.lexer.AntTokenTypes.IDENTIFIER
import com.github.antsrc.ant.lexer.AntTokenTypes.INT
import com.github.antsrc.ant.lexer.AntTokenTypes.INTERPOLATION_END
import com.github.antsrc.ant.lexer.AntTokenTypes.INTERPOLATION_START
import com.github.antsrc.ant.lexer.AntTokenTypes.KEYWORD
import com.github.antsrc.ant.lexer.AntTokenTypes.LBRACE
import com.github.antsrc.ant.lexer.AntTokenTypes.LBRACKET
import com.github.antsrc.ant.lexer.AntTokenTypes.LINE_COMMENT
import com.github.antsrc.ant.lexer.AntTokenTypes.LITERAL_KEYWORD
import com.github.antsrc.ant.lexer.AntTokenTypes.LPAREN
import com.github.antsrc.ant.lexer.AntTokenTypes.MARKUP_ENTITY
import com.github.antsrc.ant.lexer.AntTokenTypes.MARKUP_EXPR_END
import com.github.antsrc.ant.lexer.AntTokenTypes.MARKUP_EXPR_START
import com.github.antsrc.ant.lexer.AntTokenTypes.MARKUP_TEXT
import com.github.antsrc.ant.lexer.AntTokenTypes.MODULE_DOC_COMMENT
import com.github.antsrc.ant.lexer.AntTokenTypes.OPERATOR
import com.github.antsrc.ant.lexer.AntTokenTypes.PIPE
import com.github.antsrc.ant.lexer.AntTokenTypes.QDOT
import com.github.antsrc.ant.lexer.AntTokenTypes.RAW_STRING
import com.github.antsrc.ant.lexer.AntTokenTypes.RAW_TEXT
import com.github.antsrc.ant.lexer.AntTokenTypes.RBRACE
import com.github.antsrc.ant.lexer.AntTokenTypes.RBRACKET
import com.github.antsrc.ant.lexer.AntTokenTypes.REGEX
import com.github.antsrc.ant.lexer.AntTokenTypes.RESERVED
import com.github.antsrc.ant.lexer.AntTokenTypes.RPAREN
import com.github.antsrc.ant.lexer.AntTokenTypes.SEMICOLON
import com.github.antsrc.ant.lexer.AntTokenTypes.SHEBANG
import com.github.antsrc.ant.lexer.AntTokenTypes.STRING
import com.github.antsrc.ant.lexer.AntTokenTypes.STRING_END
import com.github.antsrc.ant.lexer.AntTokenTypes.STRING_START
import com.github.antsrc.ant.lexer.AntTokenTypes.STRING_TEXT
import com.github.antsrc.ant.lexer.AntTokenTypes.TAG_CLOSE
import com.github.antsrc.ant.lexer.AntTokenTypes.TAG_NAME
import com.github.antsrc.ant.lexer.AntTokenTypes.TAG_OPEN
import com.github.antsrc.ant.lexer.AntTokenTypes.TAG_SELF_CLOSE
import com.github.antsrc.ant.lexer.AntTokenTypes.WHITE_SPACE
import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/**
 * The lexer that colors Ant source (ant-lang.md sections 2 and 5). It reads the same tokens as
 * `ant`'s lexer, with the two context rules that need the token before: a `/` begins a regular
 * expression and a `<` begins markup unless the last token is an operand, and a `<` that starts a
 * line of a block begins markup even after one. A string with interpolations and markup are read
 * as several tokens, so that the code inside `${...}` and `{...}` is colored as code; a string
 * without one is a single [STRING], and a raw string a single [RAW_STRING]. It recovers from
 * anything: a malformed character is one [BAD_CHARACTER], a string or raw string left open to the
 * end of the file ends with its first line, as `ant` reads it when recovering, another unterminated
 * literal ends with its line or the file, and the errors themselves come from the language server.
 *
 * The lexer keeps a stack of what it is inside of, so its state is the whole stack: it is 0 only
 * at the start of the file and where nothing is open and no token precedes, and the editor
 * relexes from the last such point. Files are small and the lexer linear, so that is cheap.
 */
class AntLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var end = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null
    private var pos = 0
    /** The state at the start of the current token, which is what the editor keeps per token. */
    private var stateBefore = 0

    /** What the last token of the code being read was: a combination of the P_ flags. */
    private var prev = 0
    private val stack = ArrayList<Frame>()

    private class Frame(val kind: Int, val savedPrev: Int) {
        var phase = 0
        var name = ""
    }

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        end = endOffset
        tokenStart = startOffset
        tokenEnd = startOffset
        // Only the initial state can be restored; the editor restarts there (see the class doc).
        prev = 0
        stack.clear()
        advance()
    }

    override fun getState() = stateBefore

    private fun computeState(): Int {
        if (prev == 0 && stack.isEmpty()) return 0
        var h = prev
        for (f in stack) {
            h = h * 31 + f.kind + (f.phase shl 4) + (f.savedPrev shl 8) + (f.name.hashCode() shl 16)
        }
        return if (h == 0) 1 else h
    }

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
        pos = tokenStart
        stateBefore = computeState()
        tokenType = next()
        tokenEnd = pos
    }

    private fun next(): IElementType {
        val top = stack.lastOrNull()
        return when (top?.kind) {
            STRING_BODY -> stringText(top)
            TAG -> tag(top)
            CONTENT -> content(top)
            END_TAG -> endTag(top)
            RAW -> raw(top)
            else -> code()
        }
    }

    // Code

    private fun code(): IElementType {
        val c = ch(pos)
        return when {
            c == '#' && pos == 0 && ch(1) == '!' -> { toLineEnd(); SHEBANG }
            isSpace(c) -> whitespace()
            c == '/' && ch(pos + 1) == '/' -> {
                val doc = ch(pos + 2) == '!'
                toLineEnd()
                if (doc) MODULE_DOC_COMMENT else LINE_COMMENT
            }
            c == '/' && ch(pos + 1) == '*' -> blockComment()
            isDigit(c) -> number()
            isLetter(c) || c == '_' -> word()
            c == '"' -> string()
            c == '`' -> rawString()
            c == '/' && prev and P_OPERAND == 0 -> regex()
            c == '<' && markupAllowed() -> {
                pos++
                val f = push(TAG)
                f.phase = if (ch(pos) == '>') 1 else 0
                TAG_OPEN
            }
            else -> operator()
        }
    }

    /** Spaces and line breaks; a line break ends a statement by the rule of section 2 (Line endings). */
    private fun whitespace(): IElementType {
        var brokeStatement = false
        while (pos < end && isSpace(ch(pos))) {
            val c = ch(pos)
            pos++
            if ((c == '\n' || c == '\r') && !brokeStatement) {
                brokeStatement = true
                if (prev and P_HAS != 0 && prev and P_CONTINUES == 0 && (prev and P_JUMP != 0 || !nextLineContinues(pos))) {
                    prev = (prev or P_NEWLINE or P_CONTINUES) and (P_JUMP or P_DOT).inv()
                }
            }
        }
        return WHITE_SPACE
    }

    /** Whether the next line starts with `.`, `?.`, `|`, or `else`, which continues the expression. */
    private fun nextLineContinues(from: Int): Boolean {
        var i = from
        while (i < end) {
            val c = ch(i)
            when {
                isSpace(c) -> i++
                c == '/' && ch(i + 1) == '/' -> {
                    while (i < end && ch(i) != '\n' && ch(i) != '\r') i++
                }
                c == '/' && ch(i + 1) == '*' -> {
                    val j = indexOf("*/", i + 2)
                    if (j < 0) return false
                    i = j + 2
                }
                else -> {
                    if (startsWith("...", i) || startsWith("||", i)) return false
                    if (c == '.' || c == '|' || (c == '?' && ch(i + 1) == '.')) return true
                    return startsWith("else", i) && !isIdentChar(ch(i + 4))
                }
            }
        }
        return false
    }

    private fun blockComment(): IElementType {
        val j = indexOf("*/", pos + 2)
        pos = if (j < 0) end else j + 2
        return BLOCK_COMMENT
    }

    private fun number(): IElementType {
        var float = false
        if (ch(pos) == '0' && ch(pos + 1) == 'x') {
            pos += 2
            while (isHex(ch(pos)) || ch(pos) == '_') pos++
        } else if (ch(pos) == '0' && ch(pos + 1) == 'b') {
            pos += 2
            while (ch(pos) == '0' || ch(pos) == '1' || ch(pos) == '_') pos++
        } else {
            digits()
            if (ch(pos) == '.' && isDigit(ch(pos + 1))) {
                float = true
                pos++
                digits()
            }
            if (ch(pos) == 'e' && (isDigit(ch(pos + 1)) || (ch(pos + 1) == '+' || ch(pos + 1) == '-') && isDigit(ch(pos + 2)))) {
                float = true
                pos += 2
                digits()
            }
        }
        setPrev(operand = true, continues = false)
        return if (float) FLOAT else INT
    }

    private fun digits() {
        while (isDigit(ch(pos)) || ch(pos) == '_') pos++
    }

    private fun word(): IElementType {
        val start = pos
        while (isIdentChar(ch(pos))) pos++
        val text = buffer.subSequence(start, pos).toString()
        // After . or ?. and before : a keyword is a plain name (section 2, Identifiers and keywords).
        var j = pos
        while (ch(j) == ' ' || ch(j) == '\t') j++
        val plainName = prev and P_DOT != 0 || ch(j) == ':'
        return when {
            plainName || text !in AntTokenTypes.KEYWORDS && text !in AntTokenTypes.RESERVED_WORDS -> {
                setPrev(operand = true, continues = false)
                IDENTIFIER
            }
            text in AntTokenTypes.LITERAL_KEYWORDS -> {
                setPrev(operand = true, continues = false)
                LITERAL_KEYWORD
            }
            text in AntTokenTypes.KEYWORDS -> {
                val operand = text == "void"
                val jump = text == "return" || text == "break" || text == "continue"
                setPrev(operand = operand, continues = !operand && !jump, jump = text == "return")
                KEYWORD
            }
            else -> {
                setPrev(operand = false, continues = false)
                RESERVED
            }
        }
    }

    /**
     * A string at its `"` (section 2, Literals). It may span lines; one left open to the end of the
     * file ends with its first line instead. Without an interpolation it is one [STRING]; with one
     * it is a [STRING_START], its text and interpolations, and a [STRING_END].
     */
    private fun string(): IElementType {
        val oneLine = stringEnd(pos) < 0
        var i = pos + 1
        while (i < end) {
            val c = ch(i)
            if (oneLine && (c == '\n' || c == '\r')) break
            if (c == '$' && ch(i + 1) == '{') {
                pos++
                push(STRING_BODY).phase = if (oneLine) 1 else 0
                return STRING_START
            }
            i++
            if (c == '"') break
            if (c == '\\' && i < end && !(oneLine && (ch(i) == '\n' || ch(i) == '\r'))) i++
        }
        pos = i
        setPrev(operand = true, continues = false)
        return STRING
    }

    /** A raw string at its backtick: up to the next one, or to the end of its line when there is none. */
    private fun rawString(): IElementType {
        val close = indexOf("`", pos + 1)
        if (close >= 0) {
            pos = close + 1
        } else {
            pos++
            toLineEnd()
        }
        setPrev(operand = true, continues = false)
        return RAW_STRING
    }

    /**
     * The end of the string whose `"` is at from, after its closing quote, or -1 when it is open to
     * the end of the file. The code of an interpolation is skipped by its braces, the strings, raw
     * strings, and comments in it, which is enough to find where it ends.
     */
    private fun stringEnd(from: Int): Int {
        var i = from + 1
        while (i < end) {
            val c = ch(i)
            when {
                c == '"' -> return i + 1
                c == '\\' -> i += 2
                c == '$' && ch(i + 1) == '{' -> {
                    i = interpolationEnd(i + 2)
                    if (i < 0) return -1
                }
                else -> i++
            }
        }
        return -1
    }

    /** The end of the interpolation whose code starts at from, after its `}`, or -1 (see [stringEnd]). */
    private fun interpolationEnd(from: Int): Int {
        var i = from
        var depth = 0
        while (i < end) {
            val c = ch(i)
            when {
                c == '"' -> {
                    i = stringEnd(i)
                    if (i < 0) return -1
                }
                c == '`' -> {
                    val j = indexOf("`", i + 1)
                    if (j < 0) return -1
                    i = j + 1
                }
                c == '/' && ch(i + 1) == '/' -> {
                    while (i < end && ch(i) != '\n' && ch(i) != '\r') i++
                }
                c == '/' && ch(i + 1) == '*' -> {
                    val j = indexOf("*/", i + 2)
                    if (j < 0) return -1
                    i = j + 2
                }
                c == '{' -> { depth++; i++ }
                c == '}' -> {
                    if (depth == 0) return i + 1
                    depth--
                    i++
                }
                else -> i++
            }
        }
        return -1
    }

    /** A regular expression literal at its `/`, read as `ant` reads it; unterminated, it ends with its line. */
    private fun regex(): IElementType {
        pos++
        var inClass = false
        var inSpan = false
        while (pos < end) {
            val c = ch(pos)
            if (c == '\n' || c == '\r') break
            when {
                inSpan && startsWith("\\E", pos) -> { inSpan = false; pos += 2 }
                inSpan && startsWith("\\/", pos) -> pos += 2
                inSpan && c != '/' -> pos++
                c == '\\' -> {
                    if (pos + 1 >= end || ch(pos + 1) == '\n' || ch(pos + 1) == '\r') {
                        pos++
                        break
                    }
                    inSpan = !inClass && ch(pos + 1) == 'Q'
                    pos += 2
                }
                c == '[' && inClass && ch(pos + 1) == ':' -> {
                    pos += 2
                    while (pos < end && ch(pos) != '\n' && ch(pos) != '\r' && !(ch(pos) == ':' && ch(pos + 1) == ']')) pos++
                    if (ch(pos) == ':') pos += 2
                }
                c == '[' -> { inClass = true; pos++ }
                c == ']' -> { inClass = false; pos++ }
                c == '/' && !inClass -> {
                    pos++
                    while (isIdentChar(ch(pos))) pos++
                    break
                }
                else -> pos++
            }
        }
        setPrev(operand = true, continues = false)
        return REGEX
    }

    /** Whether a `<` here begins markup (section 2, Literals). */
    private fun markupAllowed(): Boolean {
        val n = ch(pos + 1)
        if (!isLetter(n) && n != '>') return false
        if (prev and P_OPERAND == 0) return true
        val statementContext = stack.isEmpty() || stack.last().kind == BRACE
        return prev and P_NEWLINE != 0 && statementContext
    }

    private fun operator(): IElementType {
        for (op in MULTI_CHAR_OPERATORS) {
            if (startsWith(op, pos)) {
                pos += op.length
                return when (op) {
                    "..." -> { setPrev(operand = false, continues = false); ELLIPSIS }
                    "?." -> { setPrev(operand = false, continues = true, dot = true); QDOT }
                    else -> { setPrev(operand = false, continues = true); OPERATOR }
                }
            }
        }
        val c = ch(pos)
        if (SINGLE_CHAR_OPERATORS.indexOf(c) < 0) {
            pos += if (Character.isHighSurrogate(c) && pos + 1 < end) 2 else 1
            setPrev(operand = false, continues = false)
            return BAD_CHARACTER
        }
        pos++
        return when (c) {
            '(' -> { push(PAREN); setPrev(operand = false, continues = true); LPAREN }
            '[' -> { push(PAREN); setPrev(operand = false, continues = true); LBRACKET }
            '{' -> { push(BRACE); setPrev(operand = false, continues = true); LBRACE }
            ')' -> { popIf(PAREN); setPrev(operand = true, continues = false); RPAREN }
            ']' -> { popIf(PAREN); setPrev(operand = true, continues = false); RBRACKET }
            '}' -> when (stack.lastOrNull()?.kind) {
                INTERPOLATION -> { pop(); INTERPOLATION_END }
                MARKUP_EXPR -> { pop(); MARKUP_EXPR_END }
                else -> { popIf(BRACE); setPrev(operand = true, continues = false); RBRACE }
            }
            ';' -> { setPrev(operand = false, continues = false); SEMICOLON }
            '.' -> { setPrev(operand = false, continues = true, dot = true); DOT }
            '|' -> { setPrev(operand = false, continues = true); PIPE }
            ',' -> { setPrev(operand = false, continues = true); COMMA }
            ':' -> { setPrev(operand = false, continues = true); COLON }
            else -> { setPrev(operand = false, continues = true); OPERATOR }
        }
    }

    // Strings with interpolations

    /** The text of a string with interpolations; f.phase is 1 when it ends with its first line. */
    private fun stringText(f: Frame): IElementType {
        val c = ch(pos)
        val oneLine = f.phase == 1
        return when {
            c == '"' -> {
                pos++
                pop()
                setPrev(operand = true, continues = false)
                STRING_END
            }
            c == '$' && ch(pos + 1) == '{' -> {
                pos += 2
                push(INTERPOLATION)
                prev = 0
                INTERPOLATION_START
            }
            oneLine && (c == '\n' || c == '\r') -> {
                // An unterminated string ends with its line, and what follows is code again.
                pop()
                setPrev(operand = true, continues = false)
                code()
            }
            else -> {
                while (pos < end) {
                    val d = ch(pos)
                    if (d == '"' || d == '$' && ch(pos + 1) == '{' || oneLine && (d == '\n' || d == '\r')) break
                    if (d == '\\' && pos + 1 < end && !(oneLine && (ch(pos + 1) == '\n' || ch(pos + 1) == '\r'))) pos++
                    pos++
                }
                STRING_TEXT
            }
        }
    }

    // Markup

    private fun tag(f: Frame): IElementType {
        val c = ch(pos)
        return when {
            isSpace(c) -> { while (isSpace(ch(pos)) && pos < end) pos++; WHITE_SPACE }
            f.phase == 0 && isLetter(c) -> {
                val start = pos
                while (isLetter(ch(pos)) || isDigit(ch(pos)) || ch(pos) == '-') pos++
                f.name = buffer.subSequence(start, pos).toString()
                f.phase = 1
                TAG_NAME
            }
            c == '>' -> {
                pos++
                stack.removeAt(stack.size - 1)
                val lower = f.name.lowercase()
                val content = Frame(if (lower == "script" || lower == "style") RAW else CONTENT, f.savedPrev)
                content.name = lower
                stack.add(content)
                TAG_CLOSE
            }
            c == '/' && ch(pos + 1) == '>' -> {
                pos += 2
                popMarkup()
                TAG_SELF_CLOSE
            }
            c == '{' -> {
                pos++
                push(MARKUP_EXPR)
                prev = 0
                MARKUP_EXPR_START
            }
            c == '=' -> { pos++; f.phase = 2; ATTR_EQ }
            c == '"' || c == '\'' -> {
                pos++
                while (pos < end && ch(pos) != c) pos++
                if (pos < end) pos++
                f.phase = 1
                ATTR_VALUE
            }
            isLetter(c) || c == '_' -> {
                while (isAttrChar(ch(pos))) pos++
                f.phase = 1
                ATTR_NAME
            }
            c == '<' -> {
                // The start tag was never closed: what follows is the element's content.
                stack[stack.size - 1] = Frame(CONTENT, f.savedPrev)
                content(stack.last())
            }
            else -> {
                pos += if (Character.isHighSurrogate(c) && pos + 1 < end) 2 else 1
                BAD_CHARACTER
            }
        }
    }

    private fun content(f: Frame): IElementType {
        val c = ch(pos)
        return when {
            c == '<' && ch(pos + 1) == '/' -> {
                pos += 2
                stack[stack.size - 1] = Frame(END_TAG, f.savedPrev)
                END_TAG_OPEN
            }
            c == '<' && isLetter(ch(pos + 1)) -> {
                pos++
                push(TAG)
                TAG_OPEN
            }
            c == '<' || c == '}' -> { pos++; BAD_CHARACTER }
            c == '{' -> {
                pos++
                push(MARKUP_EXPR)
                prev = 0
                MARKUP_EXPR_START
            }
            c == '&' && referenceEnd(pos) > 0 -> {
                pos = referenceEnd(pos)
                MARKUP_ENTITY
            }
            else -> {
                var text = !isSpace(c)
                while (pos < end) {
                    val d = ch(pos)
                    if (d == '<' || d == '{' || d == '}' || d == '&' && referenceEnd(pos) > 0) break
                    if (!isSpace(d)) text = true
                    pos++
                }
                if (text) MARKUP_TEXT else WHITE_SPACE
            }
        }
    }

    /** The end of a well-formed character reference at `&`, or -1 (section 5, Text and interpolation). */
    private fun referenceEnd(at: Int): Int {
        var i = at + 1
        if (ch(i) == '#') {
            i++
            val hex = ch(i) == 'x' || ch(i) == 'X'
            if (hex) i++
            val start = i
            while (if (hex) isHex(ch(i)) else isDigit(ch(i))) i++
            if (i == start) return -1
        } else {
            val start = i
            while (isLetter(ch(i)) || isDigit(ch(i))) i++
            if (i == start) return -1
        }
        return if (ch(i) == ';') i + 1 else -1
    }

    private fun endTag(f: Frame): IElementType {
        val c = ch(pos)
        return when {
            isSpace(c) -> { while (isSpace(ch(pos)) && pos < end) pos++; WHITE_SPACE }
            f.phase == 0 && (isLetter(c) || isDigit(c) || c == '-') -> {
                while (isLetter(ch(pos)) || isDigit(ch(pos)) || ch(pos) == '-') pos++
                f.phase = 1
                TAG_NAME
            }
            c == '>' -> {
                pos++
                popMarkup()
                TAG_CLOSE
            }
            else -> {
                // An unterminated end tag ends the element; the character belongs to what encloses it.
                popMarkup()
                next()
            }
        }
    }

    /** The text of a `<script>` or `<style>`, up to its end tag. */
    private fun raw(f: Frame): IElementType {
        var i = pos
        while (i < end) {
            if (ch(i) == '<' && ch(i + 1) == '/' && regionMatchesIgnoreCase(i + 2, f.name)) {
                val after = ch(i + 2 + f.name.length)
                if (after == '>' || after == '/' || isSpace(after) || i + 2 + f.name.length >= end) break
            }
            i++
        }
        if (i == pos) {
            pos += 2
            stack[stack.size - 1] = Frame(END_TAG, f.savedPrev)
            return END_TAG_OPEN
        }
        pos = i
        return RAW_TEXT
    }

    /** Pops a markup frame; when that closes the markup, it is an operand in the code around it. */
    private fun popMarkup() {
        val f = stack.removeAt(stack.size - 1)
        if (isCode(stack.lastOrNull()?.kind)) {
            prev = f.savedPrev
            setPrev(operand = true, continues = false)
        }
    }

    // State

    private fun push(kind: Int): Frame {
        val f = Frame(kind, prev)
        stack.add(f)
        return f
    }

    private fun pop() {
        prev = stack.removeAt(stack.size - 1).savedPrev
    }

    private fun popIf(kind: Int) {
        if (stack.lastOrNull()?.kind == kind) pop()
    }

    private fun setPrev(operand: Boolean, continues: Boolean, jump: Boolean = false, dot: Boolean = false) {
        prev = P_HAS or (if (operand) P_OPERAND else 0) or (if (continues) P_CONTINUES else 0) or
            (if (jump) P_JUMP else 0) or (if (dot) P_DOT else 0)
    }

    // Characters

    private fun ch(i: Int): Char = if (i < end && i >= 0) buffer[i] else '\u0000'

    private fun toLineEnd() {
        while (pos < end && ch(pos) != '\n' && ch(pos) != '\r') pos++
    }

    private fun startsWith(s: String, at: Int): Boolean {
        if (at + s.length > end) return false
        for (k in s.indices) if (buffer[at + k] != s[k]) return false
        return true
    }

    private fun regionMatchesIgnoreCase(at: Int, s: String): Boolean {
        if (at + s.length > end) return false
        for (k in s.indices) if (buffer[at + k].lowercaseChar() != s[k].lowercaseChar()) return false
        return true
    }

    private fun indexOf(s: String, from: Int): Int {
        var i = from
        while (i + s.length <= end) {
            if (startsWith(s, i)) return i
            i++
        }
        return -1
    }

    companion object {
        // Frame kinds: what the lexer is inside of.
        private const val PAREN = 1 // ( or [: line breaks end nothing
        private const val BRACE = 2 // {: a block or a literal
        private const val STRING_BODY = 3 // the text of a string with interpolations
        private const val INTERPOLATION = 4 // the code of a ${...}
        private const val TAG = 5 // a start tag, from its name to its >
        private const val CONTENT = 6 // the content of an element or fragment
        private const val END_TAG = 7 // an end tag, from </ to its >
        private const val RAW = 8 // the text of <script> or <style>
        private const val MARKUP_EXPR = 9 // the code of a {...} in markup

        private fun isCode(kind: Int?) = kind == null || kind == PAREN || kind == BRACE || kind == INTERPOLATION || kind == MARKUP_EXPR

        // The flags of prev.
        private const val P_HAS = 1 // a token precedes
        private const val P_OPERAND = 2 // the last token other than a line break is an operand
        private const val P_NEWLINE = 4 // a line break that ended a statement follows it
        private const val P_CONTINUES = 8 // the last token cannot end an expression
        private const val P_JUMP = 16 // the last token is return
        private const val P_DOT = 32 // the last token is . or ?.

        private val MULTI_CHAR_OPERATORS = listOf("...", "?.", "??", "==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "%=")
        private const val SINGLE_CHAR_OPERATORS = "+-*/%<>=!|.,:;()[]{}"

        private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'
        private fun isDigit(c: Char) = c in '0'..'9'
        private fun isLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
        private fun isHex(c: Char) = isDigit(c) || c in 'a'..'f' || c in 'A'..'F'
        private fun isIdentChar(c: Char) = isLetter(c) || isDigit(c) || c == '_'
        private fun isAttrChar(c: Char) = isIdentChar(c) || c == '-' || c == ':' || c == '.'
    }
}
