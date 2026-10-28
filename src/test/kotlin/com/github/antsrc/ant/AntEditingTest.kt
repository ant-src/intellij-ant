package com.github.antsrc.ant

import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AntEditingTest : BasePlatformTestCase() {
    /** Types c at the caret of before and checks the text and caret after, as `<caret>` marks them. */
    private fun typing(before: String, c: Char, after: String) {
        myFixture.configureByText("a.ant", before)
        myFixture.type(c)
        val caret = myFixture.editor.caretModel.offset
        val text = myFixture.editor.document.text
        assertEquals(after, text.substring(0, caret) + "<caret>" + text.substring(caret))
    }

    fun testEnterIndentsInsideBraces() {
        typing("func f() {<caret>}", '\n', "func f() {\n  <caret>\n}")
    }

    fun testEnterAfterOpenBraceIndents() {
        // the editor adds the closing brace of an unbalanced one
        typing("func f() {<caret>\n", '\n', "func f() {\n  <caret>\n}\n")
        typing("var xs = [1,<caret>\n", '\n', "var xs = [1,\n  <caret>\n")
        typing("var xs = [1,<caret>\n]", '\n', "var xs = [1,\n  <caret>\n]")
    }

    fun testEnterInsideNestedBracketsAndMarkup() {
        typing("var xs = [\n  f(<caret>)\n]", '\n', "var xs = [\n  f(\n    <caret>\n  )\n]")
        typing("func f(): Html {\n  <ul><caret></ul>\n}", '\n', "func f(): Html {\n  <ul>\n    <caret>\n  </ul>\n}")
        typing("func f(): Html {\n  <ul>\n    <li>a</li><caret>\n  </ul>\n}", '\n', "func f(): Html {\n  <ul>\n    <li>a</li>\n    <caret>\n  </ul>\n}")
        typing("var h = <br /><caret>\n", '\n', "var h = <br />\n<caret>\n")
        // siblings are not nested: a self-closing tag before an element, or an end tag before one
        typing("var h = <p><br /><caret><b>x</b></p>", '\n', "var h = <p><br />\n  <caret><b>x</b></p>")
        typing("var h = <p><i>a</i><caret><b>x</b></p>", '\n', "var h = <p><i>a</i>\n  <caret><b>x</b></p>")
    }

    fun testClosingBraceOutdents() {
        typing("func f() {\n  a\n  <caret>", '}', "func f() {\n  a\n}<caret>")
    }

    fun testBracesAutoClose() {
        myFixture.configureByText("a.ant", "var x = <caret>")
        myFixture.type('{')
        myFixture.checkResult("var x = {<caret>}")
        myFixture.type('"')
        myFixture.checkResult("var x = {\"<caret>\"}")
    }

    fun testLineComment() {
        myFixture.configureByText("a.ant", "var <caret>x = 1\nvar y = 2")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        assertEquals("//var x = 1\nvar y = 2", myFixture.editor.document.text)
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        assertEquals("//var x = 1\n//var y = 2", myFixture.editor.document.text)
    }

    fun testBraceMatchingAcrossMarkupAndTemplates() {
        myFixture.configureByText("a.ant", "var s = `a <caret>\${f({ x })} b`")
        val editor = myFixture.editor
        val offset = editor.caretModel.offset
        val iterator = editor.highlighter.createIterator(offset)
        assertTrue(BraceMatchingUtil.matchBrace(editor.document.charsSequence, myFixture.file.fileType, iterator, true))
        assertEquals("the matching brace is the one that ends the interpolation", "}", editor.document.text.substring(iterator.start, iterator.end))
        assertEquals(editor.document.text.lastIndexOf("}"), iterator.start)
    }

    fun testHighlighterSplitsEscapes() {
        myFixture.configureByText("a.ant", "var s = \"a\\nb\\q\"")
        val iterator = myFixture.editor.highlighter.createIterator(myFixture.editor.document.text.indexOf("\\n"))
        assertEquals("Ant:VALID_ESCAPE", iterator.tokenType.toString())
        iterator.advance(); iterator.advance()
        assertEquals("Ant:INVALID_ESCAPE", iterator.tokenType.toString())
    }
}
