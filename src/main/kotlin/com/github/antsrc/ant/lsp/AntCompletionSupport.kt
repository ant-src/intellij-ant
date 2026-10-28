package com.github.antsrc.ant.lsp

import com.github.antsrc.ant.lexer.AntTokenTypes
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.platform.lsp.api.customization.LspCompletionSupport

/**
 * The prefix the editor filters the server's items by. Inside a string, where the server offers
 * import paths (lsp.md section 4), it is the text after the last `/` or the opening quote: the
 * editor's own guess keeps the directories, `lib/`, which no file name starts with.
 */
class AntCompletionSupport : LspCompletionSupport() {
    override fun getCompletionPrefix(parameters: CompletionParameters, defaultPrefix: String): String {
        if (parameters.position.node.elementType != AntTokenTypes.STRING) return defaultPrefix
        val text = parameters.editor.document.charsSequence
        val offset = parameters.offset
        var start = offset
        while (start > 0 && text[start - 1] != '/' && text[start - 1] != '"' && text[start - 1] != '\n') start--
        return text.subSequence(start, offset).toString()
    }
}
