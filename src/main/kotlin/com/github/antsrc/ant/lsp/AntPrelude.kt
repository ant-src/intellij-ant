package com.github.antsrc.ant.lsp

import com.github.antsrc.ant.AntFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile

/**
 * The prelude's source, `ant:/prelude.d.ant`, as a read-only document: the server sends its text
 * when it starts, and Go to Declaration on a prelude name opens it (lsp.md section 4).
 */
@Service(Service.Level.PROJECT)
class AntPrelude {
    @Volatile private var file: LightVirtualFile? = null

    /** Sets the text; a document already made, and open, keeps its identity and takes the new text. */
    fun update(text: String) {
        val current = file
        if (current == null) {
            file = LightVirtualFile(NAME, AntFileType, text).also { it.isWritable = false }
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

    companion object {
        const val NAME = "prelude.d.ant"
        const val URI = "ant:/$NAME"
        fun getInstance(project: Project): AntPrelude = project.service()
    }
}
