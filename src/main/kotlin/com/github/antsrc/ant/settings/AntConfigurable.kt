package com.github.antsrc.ant.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.psi.PsiManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
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
        group("Inlay Hints") {
            row { checkBox("Inferred types of variables").bindSelected(settings::typeHints) }
            row { checkBox("Parameter names of arguments").bindSelected(settings::parameterHints) }
        }
    }

    override fun apply() {
        val path = settings.antPath
        val hints = settings.typeHints to settings.parameterHints
        super.apply()
        if (settings.antPath != path) AntLspServerSupportProvider.restartAll()
        if (settings.typeHints to settings.parameterHints != hints) refreshHints()
    }

    companion object {
        /**
         * Makes every editor show its inlay hints again by the settings. The hint passes run only
         * when the PSI changed since they last ran, so the PSI caches are dropped, which counts as
         * a change, before highlighting restarts.
         */
        fun refreshHints() {
            for (project in ProjectManager.getInstance().openProjects) {
                PsiManager.getInstance(project).dropPsiCaches()
                DaemonCodeAnalyzer.getInstance(project).restart()
            }
        }
    }
}
