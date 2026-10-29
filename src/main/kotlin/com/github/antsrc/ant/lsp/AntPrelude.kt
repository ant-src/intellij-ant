package com.github.antsrc.ant.lsp

import com.github.antsrc.ant.AntFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile

/**
 * The prelude's source as a read-only document: the server sends its text when it starts, and Go
 * to Declaration on a prelude name opens it (lsp.md section 4, Definition). The document is
 * `ant:/prelude.ant`; a server that ships the prelude as a declaration file calls it
 * `ant:/prelude.d.ant`, so both names are asked for, and the one the server answered to is kept.
 */
@Service(Service.Level.PROJECT)
class AntPrelude {
    @Volatile private var file: LightVirtualFile? = null

    /** The URI the server knows the document by, once it answered. */
    @Volatile var uri: String? = null
        private set

    /** Sets the text; a document already made, and open, keeps its identity and takes the new text. */
    fun update(uri: String, text: String) {
        this.uri = uri
        val current = file
        if (current == null) {
            file = LightVirtualFile(uri.substringAfterLast('/'), AntFileType, text).also { it.isWritable = false }
            return
        }
        if (current.content.toString() == text) return
        ApplicationManager.getApplication().invokeLater {
            WriteAction.run<RuntimeException> {
                current.setContent(null, text, true)
                FileDocumentManager.getInstance().reloadFiles(current)
            }
        }
    }

    val virtualFile: VirtualFile? get() = file

    fun isPrelude(file: VirtualFile) = file === this.file

    /** Whether a URI names the prelude document: the server has no other document of its own scheme. */
    fun isPreludeUri(fileUri: String) = fileUri.startsWith(SCHEME)

    companion object {
        private const val SCHEME = "ant:"

        /** The prelude document's URIs, the spec's first. */
        val URIS = listOf("ant:/prelude.ant", "ant:/prelude.d.ant")

        fun getInstance(project: Project): AntPrelude = project.service()
    }
}
