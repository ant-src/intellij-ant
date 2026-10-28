package com.github.antsrc.ant.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

/**
 * Requests to the running `ant lsp` that the plugin makes itself, for what the IDE's client
 * leaves out: the declaration under the mouse (Cmd+hover and Cmd+click) and the usages of a
 * declaration (lsp.md section 4, Definition and References).
 */
object AntLspRequests {
    /** The server that serves file, when it runs. */
    fun server(project: Project, file: VirtualFile): LspServer? =
        LspServerManager.getInstance(project).getServersForProvider(AntLspServerSupportProvider::class.java)
            .firstOrNull { it.state == LspServerState.Running && it.descriptor.isSupportedFile(file) }

    /**
     * Where the name at offset in file is declared. The answer is kept for the document as it is,
     * since a hover asks twice (the declaration handler and the declaration provider).
     */
    fun definitions(project: Project, file: VirtualFile, document: Document, offset: Int): List<Target> {
        val key = Triple(file.url, document.modificationStamp, offset)
        synchronized(cache) { cache[key] }?.let { return it }
        val server = server(project, file) ?: return emptyList()
        val params = DefinitionParams(server.getDocumentIdentifier(file), position(document, offset))
        val result = request(server) { it.textDocumentService.definition(params) } ?: return emptyList()
        val locations = if (result.isLeft) result.left else result.right.map { Location(it.targetUri, it.targetSelectionRange) }
        val targets = locations.mapNotNull { target(project, server, it) }
        synchronized(cache) { cache[key] = targets }
        return targets
    }

    private val cache = object : LinkedHashMap<Triple<String, Long, Int>, List<Target>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<String, Long, Int>, List<Target>>) = size > 32
    }

    /** Every use of the declaration at offset in file, without the declaration itself. */
    fun references(project: Project, file: VirtualFile, document: Document, offset: Int): List<Target> {
        val server = server(project, file) ?: return emptyList()
        val params = ReferenceParams(server.getDocumentIdentifier(file), position(document, offset), ReferenceContext(false))
        val result = request(server) { it.textDocumentService.references(params) } ?: return emptyList()
        return result.mapNotNull { target(project, server, it) }
    }

    /**
     * Asks the server and waits, on a background thread only: the editor's thread never waits on
     * the server, so a caller there gets no answer. A cancellation passes through, so that the
     * read action is run again, as the platform expects.
     */
    private fun <T> request(server: LspServer, call: (LanguageServer) -> CompletableFuture<T>): T? {
        if (ApplicationManager.getApplication().isDispatchThread) return null
        return try {
            server.sendRequestSync(REQUEST_TIMEOUT_MS, call)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.debug("request to ant lsp failed", e)
            null
        }
    }

    /** A location in a file the IDE knows: the prelude's document counts too. */
    class Target(val file: PsiFile, val range: TextRange) {
        val element get() = file.findElementAt(range.startOffset) ?: file
    }

    private fun target(project: Project, server: LspServer, location: Location): Target? {
        val file = server.descriptor.findFileByUri(location.uri) ?: return null
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        return Target(psi, range(document, location.range) ?: return null)
    }

    fun position(document: Document, offset: Int): Position {
        val line = document.getLineNumber(offset.coerceIn(0, document.textLength))
        return Position(line, offset - document.getLineStartOffset(line))
    }

    fun offset(document: Document, position: Position): Int? {
        if (position.line < 0 || position.line >= document.lineCount) return if (position.line == 0 && document.lineCount == 0) 0 else null
        val start = document.getLineStartOffset(position.line)
        return (start + position.character).coerceAtMost(document.getLineEndOffset(position.line))
    }

    fun range(document: Document, range: Range): TextRange? {
        val start = offset(document, range.start) ?: return null
        val end = offset(document, range.end) ?: return null
        return if (end >= start) TextRange(start, end) else null
    }

    private const val REQUEST_TIMEOUT_MS = 5_000
    private val LOG = logger<AntLspRequests>()
}
