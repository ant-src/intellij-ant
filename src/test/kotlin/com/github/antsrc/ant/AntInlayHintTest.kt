package com.github.antsrc.ant

import com.github.antsrc.ant.lsp.AntInlayHintSupport
import com.github.antsrc.ant.settings.AntSettings
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind

class AntInlayHintTest : BasePlatformTestCase() {
    fun testSettingsPickTheHintKinds() {
        val settings = AntSettings()
        val support = AntInlayHintSupport(settings)
        val type = InlayHint().apply { kind = InlayHintKind.Type }
        val parameter = InlayHint().apply { kind = InlayHintKind.Parameter }
        val unknown = InlayHint()
        val file = LightVirtualFile("a.ant")
        assertTrue(support.shouldAskServerForInlayHints(file))
        assertTrue(support.shouldApplyInlayHint(type) && support.shouldApplyInlayHint(parameter) && support.shouldApplyInlayHint(unknown))
        settings.typeHints = false
        assertFalse(support.shouldApplyInlayHint(type))
        assertTrue(support.shouldApplyInlayHint(parameter))
        assertTrue(support.shouldAskServerForInlayHints(file))
        settings.parameterHints = false
        assertFalse("nothing to show, so the server is not asked", support.shouldAskServerForInlayHints(file))
        assertTrue("hints of no kind are kept", support.shouldApplyInlayHint(unknown))
    }
}
