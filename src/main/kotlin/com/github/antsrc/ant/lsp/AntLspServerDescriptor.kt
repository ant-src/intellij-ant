package com.github.antsrc.ant.lsp

import com.github.antsrc.ant.AntFileType
import com.github.antsrc.ant.settings.AntExecutable
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspFormattingCustomizer
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.platform.lsp.api.customization.LspSemanticTokensCustomizer
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.services.LanguageServer

/**
 * How the IDE runs and talks to `ant lsp`: the command line, which files it serves, the prelude
 * document `ant:/prelude.ant` (lsp.md section 4, Definition), and what the IDE lets the server
 * do on top of the defaults: format a file although the plugin has an indentation model of its
 * own, and color its semantic tokens.
 */
class AntLspServerDescriptor(project: Project) : ProjectWideLspServerDescriptor(project, "Ant") {
    override fun isSupportedFile(file: VirtualFile) = file.fileType == AntFileType

    override fun getLanguageId(file: VirtualFile) = "ant"

    @Throws(ExecutionException::class)
    override fun createCommandLine(): GeneralCommandLine {
        val ant = AntExecutable.find()
        if (ant == null) {
            notifyNotFound(project)
            throw ExecutionException("The ant executable was not found. Set its path in Settings | Languages & Frameworks | Ant.")
        }
        return commandLine(ant.toString(), project)
    }

    override fun getFileUri(file: VirtualFile): String =
        if (AntPrelude.getInstance(project).isPrelude(file)) AntPrelude.URI else super.getFileUri(file)

    override fun findFileByUri(fileUri: String): VirtualFile? =
        if (fileUri == AntPrelude.URI) AntPrelude.getInstance(project).virtualFile else super.findFileByUri(fileUri)

    override val lsp4jServerClass: Class<out LanguageServer> get() = AntLanguageServer::class.java

    /** Fetches the prelude's text once the server runs, so that Go to Declaration can open it. */
    override val lspServerListener: LspServerListener = object : LspServerListener {
        override fun serverInitialized(params: InitializeResult) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val server = LspServerManager.getInstance(project)
                    .getServersForProvider(AntLspServerSupportProvider::class.java)
                    .firstOrNull { it.descriptor === this@AntLspServerDescriptor } ?: return@executeOnPooledThread
                try {
                    // The timeout is given, since the call with the default one is bound to a
                    // static helper that moved between IDE versions (2026.1.4 renamed the API).
                    val text = server.sendRequestSync(REQUEST_TIMEOUT_MS) { (it as AntLanguageServer).source(SourceParams(AntPrelude.URI)) }
                    if (text != null) AntPrelude.getInstance(project).update(text)
                } catch (e: Exception) {
                    LOG.info("ant/source failed: $e")
                }
            }
        }
    }

    override val lspCustomization: LspCustomization = object : LspCustomization() {
        override val formattingCustomizer: LspFormattingCustomizer = object : LspFormattingSupport() {
            // Reformat Code is ant fmt; the plugin's own model only indents (AntFormattingModelBuilder).
            override fun shouldFormatThisFileExclusivelyByServer(file: VirtualFile, ideCanFormatThisFileItself: Boolean, serverExplicitlyWantsToFormatThisFile: Boolean) = true
        }

        override val semanticTokensCustomizer: LspSemanticTokensCustomizer = AntSemanticTokensSupport()
    }

    private fun notifyNotFound(project: Project) {
        NotificationGroupManager.getInstance().getNotificationGroup("Ant")
            .createNotification(
                "Ant executable not found",
                "Set its path in Settings | Languages & Frameworks | Ant, or install it with: go install github.com/ant-src/ant/cmd/ant@latest",
                NotificationType.ERROR,
            )
            .addAction(NotificationAction.createSimpleExpiring("Open settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "Ant")
            })
            .notify(project)
    }

    companion object {
        private val LOG = logger<AntLspServerDescriptor>()
        private const val REQUEST_TIMEOUT_MS = 10_000

        /** `ant lsp` in the project's directory, when there is one on disk (a test project may have none). */
        fun commandLine(ant: String, project: Project): GeneralCommandLine =
            GeneralCommandLine(ant, "lsp")
                .withWorkDirectory(project.basePath?.takeIf { java.io.File(it).isDirectory })
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
    }
}
