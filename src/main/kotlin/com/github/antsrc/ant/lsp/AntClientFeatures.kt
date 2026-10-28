package com.github.antsrc.ant.lsp

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.client.features.LSPFormattingFeature
import java.net.URI

/**
 * How the client treats files: the prelude document has the URI `ant:/prelude.ant`, and
 * Reformat Code is `ant fmt` through the server, over the plugin's own indentation-only
 * formatting model.
 */
class AntClientFeatures : LSPClientFeatures() {
    init {
        setFormattingFeature(object : LSPFormattingFeature() {
            override fun isExistingFormatterOverrideable(file: PsiFile) = true
        })
    }

    override fun getFileUri(file: VirtualFile): URI? =
        if (AntPrelude.getInstance(project).isPrelude(file)) URI.create(AntPrelude.URI) else super.getFileUri(file)

    override fun findFileByUri(fileUri: String): VirtualFile? =
        if (fileUri == AntPrelude.URI) AntPrelude.getInstance(project).virtualFile else super.findFileByUri(fileUri)

    override fun toString(file: VirtualFile): String =
        if (AntPrelude.getInstance(project).isPrelude(file)) AntPrelude.URI else super.toString(file)
}
