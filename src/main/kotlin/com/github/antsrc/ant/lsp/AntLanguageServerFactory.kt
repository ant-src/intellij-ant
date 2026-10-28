package com.github.antsrc.ant.lsp

import com.github.antsrc.ant.settings.AntExecutable
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import org.eclipse.lsp4j.services.LanguageServer

class AntLanguageServerFactory : LanguageServerFactory {
    override fun createConnectionProvider(project: Project): StreamConnectionProvider {
        val ant = AntExecutable.find()
        if (ant == null) notifyNotFound(project)
        return OSProcessStreamConnectionProvider(commandLine(ant?.toString() ?: "ant", project))
    }

    override fun createLanguageClient(project: Project): LanguageClientImpl = AntLanguageClient(project)
    override fun getServerInterface(): Class<out LanguageServer> = AntLanguageServer::class.java
    override fun createClientFeatures(): LSPClientFeatures = AntClientFeatures()

    private fun notifyNotFound(project: Project) {
        NotificationGroupManager.getInstance().getNotificationGroup("Ant")
            .createNotification(
                "Ant executable not found",
                "Set its path in Settings | Languages & Frameworks | Ant, or install it with: go install github.com/ant-src/ant/cmd/ant@latest",
                NotificationType.ERROR,
            )
            .addAction(com.intellij.notification.NotificationAction.createSimpleExpiring("Open settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "Ant")
            })
            .notify(project)
    }

    companion object {
        fun commandLine(ant: String, project: Project): GeneralCommandLine =
            GeneralCommandLine(ant, "lsp").withWorkDirectory(project.basePath).withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
    }
}
