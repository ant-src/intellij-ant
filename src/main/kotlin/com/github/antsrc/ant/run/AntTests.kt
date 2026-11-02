package com.github.antsrc.ant.run

import com.github.antsrc.ant.AntFileType
import com.github.antsrc.ant.lexer.AntTokenTypes
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * A test of a test file: a top-level test, or a subtest inside one (ant-lang.md section 10, Tests).
 * [keyword] is its `test`, [range] runs from there to its closing brace, and [pattern] is the
 * `-run` pattern that selects it, as `ant lsp`'s code lenses give it (lsp.md section 4).
 *
 * A subtest whose name interpolates, as the cases of a table do, is known by its template: [name]
 * is the string's text as written, such as `case ${a}+${b}`, its pattern matches what any value
 * makes of each interpolation, so it selects every case, and [fullName] is null, since the names
 * are known only as the test runs. [fullNames] matches the full names (Test.name()) it runs under.
 */
class AntTest(
    val keyword: PsiElement,
    val name: String,
    val pattern: String,
    val range: TextRange,
    val fullName: String?,
    val fullNames: Regex,
    val depth: Int,
)

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
     * The test that ran under fullName: the one of that name, or the innermost whose pattern
     * matches it, one without interpolations before a template, as a repeat ` #2` of a name is
     * matched; else the test it is a subtest of, as for a subtest inside a function.
     */
    fun find(file: PsiFile, fullName: String): AntTest? {
        val tests = of(file)
        var name = fullName
        while (true) {
            tests.firstOrNull { it.fullName == name }?.let { return it }
            tests.filter { it.fullNames.matches(name) }.maxWithOrNull(compareBy({ it.depth }, { it.fullName != null }))?.let { return it }
            val slash = name.lastIndexOf('/')
            if (slash < 0) return null
            name = name.substring(0, slash)
        }
    }

    /**
     * `^name$` with the name quoted as a regular expression, as Go's regexp.QuoteMeta quotes it,
     * and each `/` escaped too, so it does not split the -run pattern into levels; a subtest's
     * also matches the name with ` #2`, ` #3` appended, which repeats of it get. The -run pattern
     * of a subtest appends its own to its parent's after a `/`, as `ant lsp` writes it.
     */
    fun quote(name: String, subtest: Boolean = false): String = template(listOf(name), subtest)

    /**
     * The level of a -run pattern that matches a name made of parts: text, quoted as [quote]
     * quotes a name, and between each two of them anything, which an interpolation gives.
     */
    fun template(parts: List<String>, subtest: Boolean): String = buildString {
        append('^')
        for ((i, part) in parts.withIndex()) {
            if (i > 0) append(".*")
            for (c in part) {
                if (c in """\.+*?()|[]{}^$/""") append('\\')
                append(c)
            }
        }
        if (subtest) append("( #[0-9]+)?")
        append('$')
    }

    // The PSI is the file's tokens, flat (AntParserDefinition), so the tests are found by reading
    // them: `test`, not after a dot, then a string and a block. The PSI's lexer does not know the
    // file is a test file, so `test` is a name there; it is taken either way.

    private sealed class Frame {
        object Plain : Frame()
        /** The block of a test; fullName is null under a template. */
        class Test(
            val keyword: PsiElement,
            val name: String,
            val levels: List<String>,
            val fullName: String?,
            val index: Int,
            val header: Header,
            val parentFullName: String?,
        ) : Frame()
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
                    if (frame is Frame.Test) {
                        val start = frame.keyword.textRange.startOffset
                        val range = TextRange(start, token.textRange.endOffset)
                        out.add(AntTest(frame.keyword, frame.name, frame.levels.joinToString("/"), range, frame.fullName, fullNamesOf(frame.levels), frame.levels.size - 1))
                        if (frame.header.parts.size > 1) out.addAll(cases(tokens, frame))
                    }
                }
                AntTokenTypes.IDENTIFIER, AntTokenTypes.KEYWORD -> {
                    if (token.text != "test") continue
                    val before = tokens.getOrNull(i - 1)?.node?.elementType
                    if (before == AntTokenTypes.DOT || before == AntTokenTypes.QDOT) continue
                    val header = header(tokens, i) ?: continue
                    val brace = header.brace
                    val parts = header.parts
                    val enclosing = stack.lastOrNull { it is Frame.Test } as Frame.Test?
                    if (enclosing == null && stack.isNotEmpty()) continue // neither in a test nor at the top level
                    // A top-level test's name never interpolates (section 10, Tests).
                    if (enclosing == null && parts.size != 1) continue
                    val name = if (parts.size == 1) parts[0] else file.text.substring(tokens[i + 1].textRange.endOffset, tokens[brace - 1].textRange.startOffset)
                    val levels = (enclosing?.levels ?: emptyList()) + template(parts, subtest = enclosing != null)
                    val fullName = when {
                        parts.size != 1 -> null
                        enclosing == null -> name
                        else -> enclosing.fullName?.let { "$it/$name" }
                    }
                    pending[brace] = Frame.Test(token, name, levels, fullName, i, header, enclosing?.fullName)
                }
            }
        }
        // Tests close in the order their braces do; the editor wants them in source order.
        return out.sortedBy { it.range.startOffset }
    }

    /**
     * A test's `test` and string, up to its block: [brace] is the index of the block's opening
     * brace, [parts] the text of its name around its interpolations, one part for a name without
     * any, and [interpolations] the tokens of each interpolation.
     */
    private class Header(val brace: Int, val parts: List<String>, val interpolations: List<List<PsiElement>>)

    /** The header of the test whose `test` is at i; null when no string and block follow. */
    private fun header(tokens: List<PsiElement>, i: Int): Header? {
        val next = tokens.getOrNull(i + 1) ?: return null
        return when (next.node.elementType) {
            AntTokenTypes.STRING -> {
                if (tokens.getOrNull(i + 2)?.node?.elementType != AntTokenTypes.LBRACE) return null
                Header(i + 2, listOf(unquote(next.text) ?: return null), emptyList())
            }
            AntTokenTypes.STRING_START -> {
                // The open strings and interpolations, innermost last: the name's text is the text
                // of the outermost string, outside every interpolation.
                val open = ArrayList<IElementType>()
                val parts = arrayListOf(StringBuilder())
                val interpolations = ArrayList<ArrayList<PsiElement>>()
                var j = i + 1
                while (j < tokens.size) {
                    val type = tokens[j].node.elementType
                    when (type) {
                        AntTokenTypes.STRING_START, AntTokenTypes.INTERPOLATION_START -> {
                            if (open.size > 1) interpolations.last().add(tokens[j])
                            if (type == AntTokenTypes.INTERPOLATION_START && open.size == 1) {
                                parts.add(StringBuilder())
                                interpolations.add(ArrayList())
                            }
                            open.add(type)
                        }
                        AntTokenTypes.STRING_END, AntTokenTypes.INTERPOLATION_END -> {
                            open.removeLastOrNull()
                            if (open.isEmpty()) break
                            if (open.size > 1) interpolations.last().add(tokens[j])
                        }
                        AntTokenTypes.STRING_TEXT -> if (open.size == 1) parts.last().append(tokens[j].text) else interpolations.last().add(tokens[j])
                        else -> if (open.size > 1) interpolations.last().add(tokens[j])
                    }
                    j++
                }
                if (tokens.getOrNull(j + 1)?.node?.elementType != AntTokenTypes.LBRACE) return null
                Header(j + 1, parts.map { unquote("\"$it\"") ?: return null }, interpolations)
            }
            else -> null
        }
    }


    // ---- the cases of a table

    /**
     * The cases of a table a template runs, each a test of its own at its row: when the template
     * is right inside `for p in xs`, xs is a `var` before it whose value is an array literal of
     * record literals, and each interpolation of the name is a field of the row, by a name the
     * destructuring p binds, or as `p.field`, whose value in the row is a literal (ant-lang.md
     * section 10, Tests, as GoLand runs a row of a Go table). A row whose name is not known so is
     * left out, and so is every row when the table is not of this form.
     */
    private fun cases(tokens: List<PsiElement>, test: Frame.Test): List<AntTest> {
        val open = enclosing(tokens, test.index) ?: return emptyList()
        if (tokens.getOrNull(open - 1)?.node?.elementType != AntTokenTypes.IDENTIFIER || tokens.getOrNull(open - 2)?.text != "in") return emptyList()
        val table = tokens[open - 1].text
        val loop = (open - 3 downTo 0).firstOrNull { tokens[it].text == "for" && tokens[it].node.elementType == AntTokenTypes.KEYWORD } ?: return emptyList()
        val bindings = bindings(tokens.subList(loop + 1, open - 2)) ?: return emptyList()
        // The field of the row each interpolation reads.
        val fields = test.header.interpolations.map { expr ->
            val texts = expr.map { it.text }
            when {
                texts.size == 1 -> bindings[texts[0]]?.takeIf { it.isNotEmpty() }
                texts.size == 3 && texts[1] == "." && bindings[texts[0]] == "" -> texts[2]
                else -> null
            } ?: return emptyList()
        }
        val declaration = declaration(tokens, loop, table) ?: return emptyList()
        val array = valueOf(tokens, declaration + 1) ?: return emptyList()
        if (tokens[array].node.elementType != AntTokenTypes.LBRACKET) return emptyList()
        val out = ArrayList<AntTest>()
        for (row in elements(tokens, array)) {
            val values = record(tokens, row) ?: continue
            if (fields.any { values[it] == null }) continue
            val name = buildString {
                for ((k, part) in test.header.parts.withIndex()) {
                    append(part)
                    if (k < fields.size) append(values[fields[k]])
                }
            }
            val levels = test.levels.dropLast(1) + quote(name, subtest = true)
            val range = TextRange(tokens[row.first].textRange.startOffset, tokens[row.last].textRange.endOffset)
            out.add(AntTest(tokens[row.first], name, levels.joinToString("/"), range, test.parentFullName?.let { "$it/$name" }, fullNamesOf(levels), levels.size - 1))
        }
        return out
    }

    /** What matches the full names a test of these -run levels runs under. */
    private fun fullNamesOf(levels: List<String>) = Regex(levels.joinToString("/") { it.removePrefix("^").removeSuffix("$") })

    /**
     * The index of the name of the `var` or `let` named name that is in scope at the token at i: in a
     * block around it or at the top level, before it, the nearest; not one inside another block.
     */
    private fun declaration(tokens: List<PsiElement>, i: Int, name: String): Int? {
        var depth = 0
        for (j in i - 1 downTo 1) {
            val type = tokens[j].node.elementType
            if (type in CLOSERS) depth++
            if (type in OPENERS && depth > 0) depth--
            if (depth == 0 && tokens[j].text == name && tokens[j - 1].text in BINDERS && tokens[j - 1].node.elementType == AntTokenTypes.KEYWORD) return j
        }
        return null
    }

    private val BINDERS = setOf("var", "let")
    private val OPENERS = setOf(AntTokenTypes.LBRACE, AntTokenTypes.LPAREN, AntTokenTypes.LBRACKET, AntTokenTypes.INTERPOLATION_START, AntTokenTypes.STRING_START)
    private val CLOSERS = setOf(AntTokenTypes.RBRACE, AntTokenTypes.RPAREN, AntTokenTypes.RBRACKET, AntTokenTypes.INTERPOLATION_END, AntTokenTypes.STRING_END)

    /** The index of the opening brace of the block around the token at i. */
    private fun enclosing(tokens: List<PsiElement>, i: Int): Int? {
        var depth = 0
        for (j in i - 1 downTo 0) {
            val type = tokens[j].node.elementType
            if (type in CLOSERS) depth++
            if (type in OPENERS) if (depth == 0) return j.takeIf { type == AntTokenTypes.LBRACE } else depth--
        }
        return null
    }

    /** The index of the token that closes the one at i, which opens. */
    private fun closing(tokens: List<PsiElement>, i: Int): Int? {
        var depth = 0
        for (j in i until tokens.size) {
            val type = tokens[j].node.elementType
            if (type in OPENERS) depth++
            if (type in CLOSERS && --depth == 0) return j
        }
        return null
    }

    /**
     * What a `for`'s pattern binds: each name to the field of the row it holds, or to "" for a
     * name that holds the row itself. Null for a pattern of another form.
     */
    private fun bindings(pattern: List<PsiElement>): Map<String, String>? {
        if (pattern.size == 1 && pattern[0].node.elementType == AntTokenTypes.IDENTIFIER) return mapOf(pattern[0].text to "")
        if (pattern.firstOrNull()?.node?.elementType != AntTokenTypes.LBRACE || pattern.last().node.elementType != AntTokenTypes.RBRACE) return null
        val out = HashMap<String, String>()
        for (entry in split(pattern.subList(1, pattern.size - 1))) {
            val texts = entry.map { it.text }
            when {
                texts.size == 1 && entry[0].node.elementType == AntTokenTypes.IDENTIFIER -> out[texts[0]] = texts[0]
                texts.size == 3 && texts[1] == ":" && entry[2].node.elementType == AntTokenTypes.IDENTIFIER -> out[texts[2]] = texts[0]
                else -> return null
            }
        }
        return out
    }

    /** The index of the first token of a `var`'s value, its name at i, past its type. */
    private fun valueOf(tokens: List<PsiElement>, i: Int): Int? {
        var depth = 0
        for (j in i until tokens.size) {
            val type = tokens[j].node.elementType
            if (type in OPENERS) depth++
            if (type in CLOSERS && --depth < 0) return null
            if (depth == 0 && type == AntTokenTypes.OPERATOR && tokens[j].text == "=") return (j + 1).takeIf { it < tokens.size }
        }
        return null
    }

    /** The tokens of an array literal's elements, as index ranges, the literal's `[` at i. */
    private fun elements(tokens: List<PsiElement>, i: Int): List<IntRange> {
        val end = closing(tokens, i) ?: return emptyList()
        val out = ArrayList<IntRange>()
        var start = i + 1
        var depth = 0
        for (j in i + 1..end) {
            val type = tokens[j].node.elementType
            if (j == end || (depth == 0 && type == AntTokenTypes.COMMA)) {
                if (j > start) out.add(start until j)
                start = j + 1
                continue
            }
            if (type in OPENERS) depth++
            if (type in CLOSERS) depth--
        }
        return out
    }

    /** The tokens split at the commas outside brackets, empty pieces left out. */
    private fun split(tokens: List<PsiElement>): List<List<PsiElement>> {
        val out = ArrayList<List<PsiElement>>()
        var piece = ArrayList<PsiElement>()
        var depth = 0
        for (t in tokens) {
            val type = t.node.elementType
            if (depth == 0 && type == AntTokenTypes.COMMA) {
                if (piece.isNotEmpty()) out.add(piece)
                piece = ArrayList()
                continue
            }
            if (type in OPENERS) depth++
            if (type in CLOSERS) depth--
            piece.add(t)
        }
        if (piece.isNotEmpty()) out.add(piece)
        return out
    }

    /**
     * The fields of a record literal, `Type{ ... }` or `{ ... }`, whose values are literals, as
     * `String.from` renders them; null when the row is not a record literal.
     */
    private fun record(tokens: List<PsiElement>, row: IntRange): Map<String, String>? {
        var first = row.first
        if (tokens[first].node.elementType == AntTokenTypes.IDENTIFIER) first++
        if (first > row.last || tokens[first].node.elementType != AntTokenTypes.LBRACE || closing(tokens, first) != row.last) return null
        val out = HashMap<String, String>()
        for (entry in split(tokens.subList(first + 1, row.last))) {
            if (entry.size < 3 || entry[1].node.elementType != AntTokenTypes.COLON) continue
            literal(entry.drop(2))?.let { out[entry[0].text] = it }
        }
        return out
    }

    /** A literal as `String.from` renders it: a decimal int, a string, or a bool; null for anything else. */
    private fun literal(value: List<PsiElement>): String? {
        val texts = value.map { it.text }
        return when {
            value.size == 2 && texts[0] == "-" && value[1].node.elementType == AntTokenTypes.INT && texts[1].all { it.isDigit() } -> "-" + texts[1].trimStart('0').ifEmpty { "0" }
            value.size != 1 -> null
            value[0].node.elementType == AntTokenTypes.INT && texts[0].all { it.isDigit() } -> texts[0].trimStart('0').ifEmpty { "0" }
            value[0].node.elementType == AntTokenTypes.STRING -> unquote(texts[0])
            texts[0] == "true" || texts[0] == "false" -> texts[0]
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
