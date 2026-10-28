package com.github.antsrc.ant.editing

import com.github.antsrc.ant.lexer.AntTokenTypes as T
import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler

class AntQuoteHandler : SimpleTokenSetQuoteHandler(T.STRING, T.TEMPLATE_START, T.TEMPLATE_END, T.ATTR_VALUE)
