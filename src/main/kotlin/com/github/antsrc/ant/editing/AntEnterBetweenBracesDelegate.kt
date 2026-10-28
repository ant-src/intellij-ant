package com.github.antsrc.ant.editing

import com.github.antsrc.ant.lexer.AntTokenTypes as T
import com.intellij.codeInsight.editorActions.enter.EnterBetweenBracesDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile

/**
 * Enter between `{` and `}`, `(` and `)`, `[` and `]`, or a start tag and its end tag puts the
 * caret on an indented line of its own and the closer on the next line.
 */
class AntEnterBetweenBracesDelegate : EnterBetweenBracesDelegate() {
    override fun isBracePair(lBrace: Char, rBrace: Char) =
        lBrace == '{' && rBrace == '}' || lBrace == '(' && rBrace == ')' || lBrace == '[' && rBrace == ']' || lBrace == '>' && rBrace == '<'

    /**
     * True when the two characters are not a bracket pair to split: inside one token, such as a
     * string, or a `>` and `<` that are not a start tag's end before its element's end tag.
     */
    override fun bracesAreInTheSameElement(file: PsiFile, editor: Editor, lBraceOffset: Int, rBraceOffset: Int): Boolean {
        val left = editor.highlighter.createIterator(lBraceOffset)
        val right = editor.highlighter.createIterator(rBraceOffset)
        if (left.start == right.start) return true
        if (left.tokenType != T.TAG_CLOSE) return !(left.tokenType in T.OPENERS && right.tokenType in T.CLOSERS)
        if (right.tokenType != T.END_TAG_OPEN) return true
        while (!left.atEnd() && left.tokenType != T.TAG_OPEN && left.tokenType != T.END_TAG_OPEN) left.retreat()
        return left.atEnd() || left.tokenType != T.TAG_OPEN
    }
}
