package com.github.antsrc.ant

import com.github.antsrc.ant.lsp.AntLspServerDescriptor
import com.github.antsrc.ant.lsp.AntLspServerSupportProvider
import com.github.antsrc.ant.lsp.AntPrelude
import com.github.antsrc.ant.settings.AntExecutable
import com.github.antsrc.ant.settings.AntSettings
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerManagerListener
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

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
    private var contentRoot: VirtualFile? = null
    private val events = java.util.concurrent.CopyOnWriteArrayList<String>()

    override fun setUp() {
        super.setUp()
        antPath = AntSettings.getInstance().antPath
        System.getenv("ANT_PATH")?.takeIf { it.isNotBlank() && File(it).isFile }?.let { AntSettings.getInstance().antPath = it }
    }

    override fun tearDown() {
        try {
            LspServerManager.getInstance(project).stopServers(AntLspServerSupportProvider::class.java)
            AntSettings.getInstance().antPath = antPath
            contentRoot?.let { PsiTestUtil.removeContentEntry(myFixture.module, it) }
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
        // A content root on disk: the IDE's client serves the files of the project's content.
        val dir = File(project.basePath!!, "ant-lsp").apply { mkdirs() }.also { dir = it }
        val file = File(dir, "main.ant").apply { writeText(text) }
        val dirFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!
        PsiTestUtil.addContentRoot(myFixture.module, dirFile)
        contentRoot = dirFile
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!
        val manager = LspServerManager.getInstance(project)
        manager.addLspServerManagerListener(object : LspServerManagerListener {
            override fun fileOpened(lspServer: LspServer, file: VirtualFile) { events.add("opened ${file.name}") }
            override fun diagnosticsReceived(lspServer: LspServer, file: VirtualFile) { events.add("diagnostics ${file.name}") }
            override fun serverStateChanged(lspServer: LspServer) { events.add("state ${lspServer.state}") }
        }, testRootDisposable, true)
        myFixture.openFileInEditor(virtualFile)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("Str") + 3)

        manager.ensureServerStarted(AntLspServerSupportProvider::class.java, AntLspServerDescriptor(project))
        waitFor("the server to run") {
            manager.getServersForProvider(AntLspServerSupportProvider::class.java).any { it.state == LspServerState.Running }
        }

        var infos: List<HighlightInfo> = emptyList()
        waitFor("a diagnostic on the bad assignment") {
            infos = myFixture.doHighlighting()
            infos.any { it.severity == HighlightSeverity.ERROR && it.text == "\"text\"" }
        }
        val errors = infos.filter { it.severity == HighlightSeverity.ERROR }.map { "${it.text}: ${it.description}" }
        assertTrue(errors.toString(), errors.any { it.startsWith("\"text\": cannot use string where int is expected") })
        assertTrue(errors.toString(), errors.any { it == "Str: Str is not defined" })

        // With one match the editor inserts it and returns no list.
        val items = myFixture.completeBasic()
        if (items == null) {
            assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("var s = String"))
        } else {
            assertTrue("completion offers String: " + items.map { it.lookupString }, items.any { it.lookupString == "String" })
        }

        // Members after a dot inside a template interpolation, and import paths inside the string.
        File(dir, "lib").mkdirs()
        File(dir, "lib/util.ant").writeText("export func twice(n: int): int { n * 2 }\n")
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(dir, "lib"))
        val results = ArrayList<String>()
        val cases = listOf(
            "var m = Math.<caret>\n" to "PI",
            "var t = `${'$'}{Math.<caret>}`\n" to "PI",
            "import \"./lib/<caret>\"\n" to "util.ant",
        )
        for ((i, case) in cases.withIndex()) {
            val (source, expected) = case
            val caret = source.indexOf("<caret>")
            val text2 = source.replace("<caret>", "")
            val f = File(dir, "case$i.ant").apply { writeText(text2) }
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)!!
            myFixture.openFileInEditor(vf)
            waitFor("the client to open ${f.name}") { events.contains("opened ${f.name}") }
            waitFor("diagnostics for ${f.name}") { events.contains("diagnostics ${f.name}") }
            myFixture.editor.caretModel.moveToOffset(caret)
            val found = myFixture.completeBasic()
            val got = found?.map { it.lookupString } ?: listOf("<inserted: ${myFixture.editor.document.text.trim()}>")
            val ok = found == null && myFixture.editor.document.text.contains(expected) || found != null && got.contains(expected)
            results.add("${source.trim()} -> ${if (ok) "ok" else "MISSING $expected in $got"}")
        }
        assertTrue(results.joinToString("\n"), results.none { "MISSING" in it })

        waitFor("the prelude source") { AntPrelude.getInstance(project).virtualFile != null }
        val prelude = AntPrelude.getInstance(project).virtualFile!!
        assertFalse(prelude.isWritable)
        assertTrue(String(prelude.contentsToByteArray()).contains("func String."))
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                val servers = LspServerManager.getInstance(project).getServersForProvider(AntLspServerSupportProvider::class.java)
                fail("timed out waiting for $what; servers ${servers.map { it.state }}, events $events, highlights ${myFixture.doHighlighting().map { "${it.severity}:${it.text}:${it.description}" }}")
            }
            NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(100)
        }
    }
}
