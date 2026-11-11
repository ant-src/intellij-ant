package com.github.antsrc.ant.lsp

import com.github.antsrc.ant.psi.AntFile
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.lsp.api.LspServer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.RenameHandler
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture

/**
 * Rename, asked of the server (lsp.md section 4, References and rename). The IDE's own LSP client
 * renames only in plain text and TextMate files, and before 2026.3 not at all, so the plugin does
 * it: the server says which name stands at the caret, a dialog asks for the new one, and the
 * server's edits are applied as one command. What the server refuses is shown at the caret.
 */
class AntRenameHandler : RenameHandler, DumbAware {
    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return false
        val file = CommonDataKeys.PSI_FILE.getData(dataContext) as? AntFile ?: return false
        val project = editor.project ?: return false
        return AntLspRequests.server(project, file.virtualFile ?: return false) != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext?) {
        if (editor == null) return
        val virtualFile = file?.virtualFile ?: return
        val server = AntLspRequests.server(project, virtualFile) ?: return
        val document = editor.document
        val id = server.getDocumentIdentifier(virtualFile)
        val position = AntLspRequests.position(document, editor.caretModel.offset)

        val prepared = ask(server, "Looking for the name to rename") { it.textDocumentService.prepareRename(PrepareRenameParams(id, position)) }
        val range = prepared.value?.let { if (it.isFirst) it.first else if (it.isSecond) it.second.range else null }?.let { AntLspRequests.range(document, it) }
        if (range == null) return hint(editor, prepared.error ?: "Nothing to rename here")

        val old = document.getText(range)
        val name = Messages.showInputDialog(project, "Rename $old to:", "Rename", null, old, NotBlank)?.trim() ?: return
        if (name == old) return
        val renamed = ask(server, "Renaming $old to $name") { it.textDocumentService.rename(RenameParams(id, position, name)) }
        val edit = renamed.value ?: return hint(editor, renamed.error ?: "ant lsp did not answer")
        apply(project, server, edit)?.let { hint(editor, it) }
    }

    /** Rename is asked from an editor only; the project view has no name under a caret. */
    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {}

    /** Applies the server's edits, in every file they name, as one command; returns what went wrong. */
    private fun apply(project: Project, server: LspServer, edit: WorkspaceEdit): String? {
        val changes = LinkedHashMap<Document, List<Pair<TextRange, String>>>()
        val files = ArrayList<VirtualFile>()
        for ((uri, edits) in edit.changes.orEmpty()) {
            val file = server.descriptor.findFileByUri(uri) ?: return "Cannot find $uri"
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return "Cannot edit ${file.name}"
            // Every range is of the document as the server saw it, so all are read before any edit.
            changes[document] = edits.map { (AntLspRequests.range(document, it.range) ?: return "ant lsp sent an edit outside ${file.name}") to it.newText }
            files.add(file)
        }
        if (ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(files).hasReadonlyFiles()) return null
        WriteCommandAction.writeCommandAction(project).withName("Rename").run<RuntimeException> {
            for ((document, edits) in changes) {
                for ((range, text) in edits.sortedByDescending { it.first.startOffset }) document.replaceString(range.startOffset, range.endOffset, text)
            }
        }
        // The server reads from disk the files no editor has open, so they are saved for it.
        for (document in changes.keys) FileDocumentManager.getInstance().saveDocument(document)
        return null
    }

    private fun hint(editor: Editor, message: String) = HintManager.getInstance().showErrorHint(editor, message)

    private class Answer<T>(val value: T?, val error: String?)

    /** Asks the server under a modal progress, and keeps the message of an error it answers with. */
    private fun <T> ask(server: LspServer, title: String, call: (LanguageServer) -> CompletableFuture<T>): Answer<T> =
        runWithModalProgressBlocking(server.project, title) {
            server.sendRequest { call(it).handle { value, error -> Answer(value, error?.let(::message)) } } ?: Answer(null, null)
        }

    private fun message(error: Throwable): String {
        val response = generateSequence(error) { it.cause }.filterIsInstance<ResponseErrorException>().firstOrNull()
        return (response?.responseError?.message ?: error.message ?: error.toString()).replaceFirstChar { it.uppercase() }
    }

    private object NotBlank : InputValidator {
        override fun checkInput(inputString: String?) = !inputString.isNullOrBlank()
        override fun canClose(inputString: String?) = checkInput(inputString)
    }
}
