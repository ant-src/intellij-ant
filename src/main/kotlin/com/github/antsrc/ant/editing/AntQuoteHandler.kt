package com.github.antsrc.ant.editing

import com.github.antsrc.ant.lexer.AntTokenTypes as T
import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.openapi.editor.highlighter.HighlighterIterator

class AntQuoteHandler : SimpleTokenSetQuoteHandler(T.STRING, T.STRING_START, T.STRING_END, T.RAW_STRING, T.ATTR_VALUE) {
    /** A raw string is closed by a backtick, which the default, for `"` and `'`, does not know. */
    override fun isNonClosedLiteral(iterator: HighlighterIterator, chars: CharSequence): Boolean {
        if (iterator.tokenType == T.RAW_STRING) {
            return iterator.end - iterator.start < 2 || chars[iterator.end - 1] != '`'
        }
        return super.isNonClosedLiteral(iterator, chars)
    }
}
