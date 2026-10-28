package com.github.antsrc.ant

import com.github.antsrc.ant.lsp.AntPrelude
import com.github.antsrc.ant.settings.AntExecutable
import com.github.antsrc.ant.settings.AntLsp
import com.github.antsrc.ant.settings.AntSettings
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.JSONUtils
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.LanguageServerItem
import org.eclipse.lsp4j.Diagnostic
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Starts the real `ant lsp`, from ANT_PATH or wherever [AntExecutable] finds it, and checks that
 * diagnostics, completion, and the prelude document reach the editor. Without an executable the
 * test passes without checking anything, and says so. The document is a file on disk: the server
 * analyzes only `file:` documents (lsp.md section 2), and the light test fixture's own files are
 * in memory.
 */
class AntLspTest : BasePlatformTestCase() {
    private var antPath = ""
    private var dir: File? = null

    override fun setUp() {
        super.setUp()
        antPath = AntSettings.getInstance().antPath
        System.getenv("ANT_PATH")?.takeIf { it.isNotBlank() && File(it).isFile }?.let { AntSettings.getInstance().antPath = it }
    }

    override fun tearDown() {
        try {
            LanguageServerManager.getInstance(project).stop(AntLsp.SERVER_ID)
            AntSettings.getInstance().antPath = antPath
            dir?.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun antAvailable(): Boolean {
        if (AntExecutable.find() != null) return true
        println("AntLspTest: no ant executable; set ANT_PATH to run the language server test")
        return false
    }

    fun testDiagnosticsCompletionAndPrelude() {
        if (!antAvailable()) return
        val text = "var n: int = \"text\"\nvar s = Str\n"
        val dir = Files.createTempDirectory("ant-lsp").toFile().canonicalFile.also { dir = it }
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path)
        val file = File(dir, "main.ant").apply { writeText(text) }
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!
        myFixture.openFileInEditor(virtualFile)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("Str") + 3)

        val server = LanguageServerManager.getInstance(project).getLanguageServer(AntLsp.SERVER_ID).get(30, TimeUnit.SECONDS)
        assertNotNull("the server definition is known", server)
        waitFor("the server to start") { server!!.serverWrapper.serverStatus == ServerStatus.started }
        // The diagnostics the server published for the document, as LSP4IJ keeps them before it
        // paints them in the editor (which happens outside the highlighting passes a test can run).
        var diagnostics: List<Diagnostic> = emptyList()
        waitFor("the diagnostics of the document") {
            diagnostics = diagnostics(server!!, virtualFile)
            diagnostics.size == 2
        }
        // Read as JSON: LSP4J's Diagnostic API differs between the version LSP4IJ runs and the
        // one the platform bundles, which the test is compiled against.
        val messages = diagnostics.map { JSONUtils.getLsp4jGson().toJson(it) }
        assertTrue(messages.toString(), messages.any { it.contains("cannot use string where int is expected") })
        assertTrue(messages.toString(), messages.any { it.contains("Str is not defined") })
        assertTrue(messages.toString(), messages.all { it.contains("\"severity\":1") })

        val items = myFixture.completeBasic()
        assertNotNull("completion items", items)
        assertTrue("completion offers String: " + items!!.map { it.lookupString }, items.any { it.lookupString == "String" })

        waitFor("the prelude source") { AntPrelude.getInstance(project).virtualFile != null }
        val prelude = AntPrelude.getInstance(project).virtualFile!!
        assertFalse(prelude.isWritable)
        assertTrue(String(prelude.contentsToByteArray()).contains("func String."))
    }

    private fun diagnostics(server: LanguageServerItem, file: VirtualFile): List<Diagnostic> =
        server.serverWrapper.getOpenedDocument(LSPIJUtils.toUri(file))?.diagnostics?.toList() ?: emptyList()

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                val item = LanguageServerManager.getInstance(project).getLanguageServer(AntLsp.SERVER_ID).getNow(null)
                fail("timed out waiting for $what; server status ${item?.serverWrapper?.serverStatus}, documents ${item?.serverWrapper?.openedDocuments?.map { it.toString() }}")
            }
            NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(100)
        }
    }
}
