package com.github.antsrc.ant.run

import com.github.antsrc.ant.AntFileType
import com.github.antsrc.ant.lexer.AntTokenTypes
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * A test of a test file whose name is known without running anything: a top-level test, or a
 * subtest whose name is a string without interpolation inside one (ant-lang.md section 10, Tests).
 * [keyword] is its `test`, [range] runs from there to its closing brace, and [pattern] is the
 * `-run` pattern that selects it alone, as `ant lsp`'s code lenses give it (lsp.md section 4).
 */
class AntTest(val keyword: PsiElement, val name: String, val pattern: String, val range: TextRange)

object AntTests {
    /** The tests of file, in source order; none when it is not a test file. */
    fun of(file: PsiFile): List<AntTest> {
        if (!AntFileType.isTestFile(file.name)) return emptyList()
        return CachedValuesManager.getCachedValue(file) { CachedValueProvider.Result.create(scan(file), file) }
    }

    /** The test whose `test` is element, if it is one. */
    fun at(element: PsiElement): AntTest? {
        val file = element.containingFile ?: return null
        return of(file).firstOrNull { it.keyword == element }
    }

    /** The innermost test around offset. */
    fun around(file: PsiFile, offset: Int): AntTest? = of(file).lastOrNull { offset in it.range.startOffset until it.range.endOffset }

    /**
     * `^name$` with the name quoted as a regular expression, as Go's regexp.QuoteMeta quotes it,
     * and each `/` escaped too, so it does not split the -run pattern into levels; a subtest's
     * also matches the name with ` #2`, ` #3` appended, which repeats of it get. The -run pattern
     * of a subtest appends its own to its parent's after a `/`, as `ant lsp` writes it.
     */
    fun quote(name: String, subtest: Boolean = false): String = buildString {
        append('^')
        for (c in name) {
            if (c in """\.+*?()|[]{}^$/""") append('\\')
            append(c)
        }
        if (subtest) append("( #[0-9]+)?")
        append('$')
    }

    // The PSI is the file's tokens, flat (AntParserDefinition), so the tests are found by reading
    // them: `test`, not after a dot, then a string and a block. The PSI's lexer does not know the
    // file is a test file, so `test` is a name there; it is taken either way.

    private sealed class Frame {
        object Plain : Frame()
        /** The block of a test whose name is known, or of a subtest whose name is not (null). */
        class Test(val keyword: PsiElement, val name: String?, val pattern: String?) : Frame()
    }

    private fun scan(file: PsiFile): List<AntTest> {
        val tokens = generateSequence(file.firstChild) { it.nextSibling }
            .filter { it.node.elementType != AntTokenTypes.WHITE_SPACE && it.node.elementType !in AntTokenTypes.COMMENTS }
            .toList()
        val out = ArrayList<AntTest>()
        val stack = ArrayList<Frame>()
        // The frame each test's opening brace starts, by the brace's index.
        val pending = HashMap<Int, Frame.Test>()
        for ((i, token) in tokens.withIndex()) {
            when (token.node.elementType) {
                AntTokenTypes.LBRACE -> stack.add(pending.remove(i) ?: Frame.Plain)
                AntTokenTypes.RBRACE -> {
                    val frame = stack.removeLastOrNull()
                    if (frame is Frame.Test && frame.pattern != null) {
                        val start = frame.keyword.textRange.startOffset
                        out.add(AntTest(frame.keyword, frame.name!!, frame.pattern, TextRange(start, token.textRange.endOffset)))
                    }
                }
                AntTokenTypes.IDENTIFIER, AntTokenTypes.KEYWORD -> {
                    if (token.text != "test") continue
                    val before = tokens.getOrNull(i - 1)?.node?.elementType
                    if (before == AntTokenTypes.DOT || before == AntTokenTypes.QDOT) continue
                    val header = header(tokens, i) ?: continue
                    val (brace, name) = header
                    val enclosing = stack.lastOrNull { it is Frame.Test } as Frame.Test?
                    if (enclosing == null && stack.isNotEmpty()) continue // neither in a test nor at the top level
                    val pattern = when {
                        name == null -> null
                        enclosing == null -> quote(name)
                        else -> enclosing.pattern?.let { it + "/" + quote(name, subtest = true) }
                    }
                    pending[brace] = Frame.Test(token, name, pattern)
                }
            }
        }
        // Tests close in the order their braces do; the editor wants them in source order.
        return out.sortedBy { it.range.startOffset }
    }

    /**
     * The index of the opening brace of the test whose `test` is at i, and its name, or null for
     * a name with interpolations; null when no string and block follow.
     */
    private fun header(tokens: List<PsiElement>, i: Int): Pair<Int, String?>? {
        val next = tokens.getOrNull(i + 1) ?: return null
        return when (next.node.elementType) {
            AntTokenTypes.STRING -> {
                if (tokens.getOrNull(i + 2)?.node?.elementType != AntTokenTypes.LBRACE) return null
                Pair(i + 2, unquote(next.text) ?: return null)
            }
            AntTokenTypes.STRING_START -> {
                var depth = 0
                var j = i + 1
                while (j < tokens.size) {
                    when (tokens[j].node.elementType) {
                        AntTokenTypes.STRING_START -> depth++
                        AntTokenTypes.STRING_END -> if (--depth == 0) break
                    }
                    j++
                }
                if (tokens.getOrNull(j + 1)?.node?.elementType != AntTokenTypes.LBRACE) return null
                Pair(j + 1, null)
            }
            else -> null
        }
    }

    /** The value of a string literal without interpolation (section 2, Literals), or null when it is malformed. */
    fun unquote(literal: String): String? {
        if (literal.length < 2 || !literal.startsWith('"') || !literal.endsWith('"')) return null
        val body = literal.substring(1, literal.length - 1)
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i++]
            if (c != '\\') {
                out.append(c)
                continue
            }
            if (i >= body.length) return null
            when (val e = body[i++]) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                '\\', '"', '$' -> out.append(e)
                'u' -> {
                    if (body.getOrNull(i) != '{') return null
                    val close = body.indexOf('}', i)
                    if (close < 0) return null
                    val v = body.substring(i + 1, close).toIntOrNull(16) ?: return null
                    if (v !in 0..0x10FFFF || v in 0xD800..0xDFFF) return null
                    out.appendCodePoint(v)
                    i = close + 1
                }
                else -> return null
            }
        }
        return out.toString()
    }
}
