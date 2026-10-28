package com.github.antsrc.ant

import com.github.antsrc.ant.lexer.AntTokenTypes
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Lexes every `.ant` file of the ant repository, which the ANT_REPO variable names (by default
 * the sibling checkout): a valid program has no bad character and leaves nothing open. The
 * tables under tests/stdlib are not programs but session inputs, each lexed on its own, so a
 * line there may start with a `/` that is a regular expression only in a fresh session; they
 * are left out.
 */
class AntLexerCorpusTest {
    @Test
    fun corpusLexesClean() {
        val repo = File(System.getenv("ANT_REPO") ?: "../ant")
        assumeTrue("no ant repository at $repo", repo.isDirectory)
        val dirs = listOf("tests/run", "tests/check", "tests/check/lib", "tests/unchecked", "examples", "examples/lib", "examples/lib/shapes")
        var files = 0
        val problems = ArrayList<String>()
        for (dir in dirs) {
            val full = File(repo, dir)
            if (!full.isDirectory) continue
            for (f in full.listFiles()!!.filter { it.name.endsWith(".ant") }.sortedBy { it.name }) {
                files++
                val src = f.readText()
                val toks = lex(src)
                toks.filter { it.type == AntTokenTypes.BAD_CHARACTER }.forEach { problems.add("$dir/${f.name}: bad character ${it.text}") }
                if (!endsBalanced(src)) problems.add("$dir/${f.name}: something is left open at the end")
            }
        }
        assertTrue("only $files corpus files under $repo", files > 100)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
