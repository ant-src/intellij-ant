package com.github.antsrc.ant.lsp

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.ServerStatus
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl

class AntLanguageClient(project: Project) : LanguageClientImpl(project) {
    override fun handleServerStatusChanged(serverStatus: ServerStatus) {
        if (serverStatus != ServerStatus.started) return
        val server = languageServer as? AntLanguageServer ?: return
        server.source(SourceParams(AntPrelude.URI)).whenComplete { text, err ->
            if (text != null) AntPrelude.getInstance(project).update(text)
            if (err != null) LOG.info("ant/source failed: $err")
        }
    }

    companion object {
        private val LOG = logger<AntLanguageClient>()
    }
}
