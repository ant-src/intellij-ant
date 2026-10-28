package com.github.antsrc.ant.lsp

import com.github.antsrc.ant.AntFileType
import com.github.antsrc.ant.AntIcons
import com.github.antsrc.ant.settings.AntConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.lsWidget.LspServerWidgetItem

/** Starts `ant lsp` for a project when its first Ant file is opened (lsp.md section 1). */
class AntLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(project: Project, file: VirtualFile, serverStarter: LspServerSupportProvider.LspServerStarter) {
        if (file.fileType == AntFileType) {
            serverStarter.ensureServerStarted(AntLspServerDescriptor(project))
        }
    }

    override fun createLspServerWidgetItem(lspServer: LspServer, currentFile: VirtualFile?) =
        LspServerWidgetItem(lspServer, currentFile, AntIcons.FILE, AntConfigurable::class.java)

    companion object {
        /** Restarts the server of every open project, after the executable changed. */
        fun restartAll() {
            for (project in com.intellij.openapi.project.ProjectManager.getInstance().openProjects) {
                project.putUserData(AntLspServerDescriptor.NOTIFIED, null)
                LspServerManager.getInstance(project).stopAndRestartIfNeeded(AntLspServerSupportProvider::class.java)
            }
        }
    }
}
