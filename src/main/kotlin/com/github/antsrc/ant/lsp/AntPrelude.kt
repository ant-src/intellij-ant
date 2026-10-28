package com.github.antsrc.ant.lsp

import com.github.antsrc.ant.AntFileType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile

/**
 * The prelude's source, `ant:/prelude.ant`, as a read-only document: the server sends its text
 * when it starts, and Go to Declaration on a prelude name opens it (lsp.md section 4).
 */
@Service(Service.Level.PROJECT)
class AntPrelude {
    @Volatile private var file: LightVirtualFile? = null

    fun update(text: String) {
        val current = file
        if (current != null && current.content.toString() == text) return
        file = LightVirtualFile(NAME, AntFileType, text).also { it.isWritable = false }
    }

    val virtualFile: VirtualFile? get() = file

    fun isPrelude(file: VirtualFile) = file === this.file

    companion object {
        const val NAME = "prelude.ant"
        const val URI = "ant:/$NAME"
        fun getInstance(project: Project): AntPrelude = project.service()
    }
}
