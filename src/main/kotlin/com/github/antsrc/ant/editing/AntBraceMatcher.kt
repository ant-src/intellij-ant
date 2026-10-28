package com.github.antsrc.ant.editing

import com.github.antsrc.ant.lexer.AntTokenTypes as T
import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

class AntBraceMatcher : PairedBraceMatcher {
    override fun getPairs() = PAIRS
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?) = true
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int) = openingBraceOffset

    companion object {
        private val PAIRS = arrayOf(
            BracePair(T.LBRACE, T.RBRACE, true),
            BracePair(T.LPAREN, T.RPAREN, false),
            BracePair(T.LBRACKET, T.RBRACKET, false),
            BracePair(T.INTERPOLATION_START, T.INTERPOLATION_END, false),
            BracePair(T.MARKUP_EXPR_START, T.MARKUP_EXPR_END, false),
            BracePair(T.TAG_OPEN, T.TAG_CLOSE, false),
            BracePair(T.TAG_OPEN, T.TAG_SELF_CLOSE, false),
            BracePair(T.END_TAG_OPEN, T.TAG_CLOSE, false),
        )
    }
}
