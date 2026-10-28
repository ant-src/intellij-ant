package com.github.antsrc.ant.lsp

import com.github.antsrc.ant.settings.AntSettings
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.customization.LspInlayHintSupport
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind

/**
 * Which of the server's inlay hints the editor shows (lsp.md section 4): the inferred types and
 * the parameter names each have a switch in Settings | Languages & Frameworks | Ant.
 */
class AntInlayHintSupport(private val settings: AntSettings = AntSettings.getInstance()) : LspInlayHintSupport() {
    override fun shouldAskServerForInlayHints(file: VirtualFile) = settings.typeHints || settings.parameterHints

    override fun shouldApplyInlayHint(inlayHint: InlayHint) = when (inlayHint.kind) {
        InlayHintKind.Type -> settings.typeHints
        InlayHintKind.Parameter -> settings.parameterHints
        else -> true
    }
}
