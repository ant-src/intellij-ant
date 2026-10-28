package com.github.antsrc.ant.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.github.antsrc.ant.lsp.AntLspServerSupportProvider

/** Settings | Languages & Frameworks | Ant. */
class AntConfigurable : BoundConfigurable("Ant") {
    private val settings = AntSettings.getInstance()

    override fun createPanel() = panel {
        row("Ant executable:") {
            val field = TextFieldWithBrowseButton()
            field.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Ant Executable"))
            cell(field).align(AlignX.FILL)
                .bindText({ settings.antPath }, { settings.antPath = it.trim() })
                .comment("Empty means ant on PATH, then in \$GOBIN, then in the bin directory of go env GOPATH, then ~/go/bin.<br>" +
                    "Install it with: go install github.com/ant-src/ant/cmd/ant@latest")
        }
    }

    override fun apply() {
        val before = settings.antPath
        super.apply()
        if (settings.antPath != before) AntLspServerSupportProvider.restartAll()
    }
}
