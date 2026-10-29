package com.github.antsrc.ant.debug

import com.github.antsrc.ant.AntFileType
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider

/**
 * The editor of an Ant expression: Evaluate Expression, a watch, a breakpoint's condition and
 * the expression it logs. The IDE shows a breakpoint's condition and log fields only to a
 * breakpoint type that has one.
 */
object AntEditorsProvider : XDebuggerEditorsProvider() {
    override fun getFileType() = AntFileType
    override fun createDocument(project: Project, expression: XExpression, sourcePosition: XSourcePosition?, mode: EvaluationMode): Document =
        EditorFactory.getInstance().createDocument(expression.expression)
}
