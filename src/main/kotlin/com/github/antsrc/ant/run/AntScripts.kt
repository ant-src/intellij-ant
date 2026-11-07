package com.github.antsrc.ant.run

import com.github.antsrc.ant.AntFileType
import com.github.antsrc.ant.lexer.AntTokenTypes
import com.github.antsrc.ant.psi.AntFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.TokenType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil

/**
 * Whether an Ant file is a script: a module with a statement at its top level, which `ant run`
 * runs; any other module is a library (ant-lang.md section 10, Scripts). A test file never is one.
 * The file's tokens are flat, so a top-level item is a token at no depth of brackets that begins a
 * line the line before does not continue; it is a statement unless it begins with `import`,
 * `export`, `func`, `type`, `var`, or `let`. Nor is anything inside markup, a start tag's
 * attributes included, a statement. A file with a `#!` line is taken for a script too.
 */
object AntScripts {
    private val DECLARATIONS = setOf("import", "export", "func", "type", "var", "let")

    /** What ends a line that the next one continues. */
    private val CONTINUES = TokenSet.create(
        AntTokenTypes.OPERATOR, AntTokenTypes.COMMA, AntTokenTypes.DOT, AntTokenTypes.QDOT,
        AntTokenTypes.COLON, AntTokenTypes.PIPE,
    )

    /** What begins a line that continues the one before. */
    private val CONTINUATIONS = TokenSet.orSet(
        TokenSet.create(AntTokenTypes.OPERATOR, AntTokenTypes.DOT, AntTokenTypes.QDOT, AntTokenTypes.PIPE),
        AntTokenTypes.CLOSERS,
    )

    /** Markup: its text is no statement, and its tags nest by rules of their own. */
    private val MARKUP = TokenSet.create(
        AntTokenTypes.TAG_OPEN, AntTokenTypes.END_TAG_OPEN, AntTokenTypes.TAG_CLOSE, AntTokenTypes.TAG_SELF_CLOSE,
        AntTokenTypes.TAG_NAME, AntTokenTypes.ATTR_NAME, AntTokenTypes.ATTR_EQ, AntTokenTypes.ATTR_VALUE,
        AntTokenTypes.MARKUP_TEXT, AntTokenTypes.MARKUP_ENTITY, AntTokenTypes.RAW_TEXT,
    )

    private val OPEN = TokenSet.create(
        AntTokenTypes.LBRACE, AntTokenTypes.LPAREN, AntTokenTypes.LBRACKET,
        AntTokenTypes.INTERPOLATION_START, AntTokenTypes.MARKUP_EXPR_START,
    )
    private val CLOSE = TokenSet.create(
        AntTokenTypes.RBRACE, AntTokenTypes.RPAREN, AntTokenTypes.RBRACKET,
        AntTokenTypes.INTERPOLATION_END, AntTokenTypes.MARKUP_EXPR_END,
    )

    fun isScript(file: AntFile): Boolean = CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(compute(file), file)
    }

    private fun compute(file: AntFile): Boolean {
        if (AntFileType.isTestFile(file.name)) return false
        var depth = 0
        // markup counts the elements open around a token: a start tag opens one, and an end tag or
        // a self-closing tag's /> closes it.
        var markup = 0
        var newLine = true
        var last: PsiElement? = null
        var leaf: PsiElement? = file.firstChild?.let(PsiTreeUtil::firstChild)
        while (leaf != null) {
            val type = leaf.node.elementType
            when {
                type == AntTokenTypes.SHEBANG -> return true
                leaf is PsiWhiteSpace || type == TokenType.WHITE_SPACE -> if ('\n' in leaf.text) newLine = true
                type in AntTokenTypes.COMMENTS -> if (type == AntTokenTypes.LINE_COMMENT) newLine = true
                else -> {
                    val starts = depth == 0 && markup == 0 && newLine && type !in MARKUP && type !in CONTINUATIONS &&
                        last?.node?.elementType.let { it == null || it !in CONTINUES } &&
                        !(type == AntTokenTypes.KEYWORD && leaf.text == "else")
                    if (starts && !(type == AntTokenTypes.KEYWORD && leaf.text in DECLARATIONS)) return true
                    if (type in OPEN) depth++
                    if (type in CLOSE && depth > 0) depth--
                    if (type == AntTokenTypes.TAG_OPEN) markup++
                    if ((type == AntTokenTypes.TAG_SELF_CLOSE || type == AntTokenTypes.END_TAG_OPEN) && markup > 0) markup--
                    newLine = false
                    last = leaf
                }
            }
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        return false
    }
}
