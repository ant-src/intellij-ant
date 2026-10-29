package com.github.antsrc.ant

import com.github.antsrc.ant.lsp.AntLspServerDescriptor
import com.github.antsrc.ant.lsp.AntLspServerSupportProvider
import com.github.antsrc.ant.lsp.AntPrelude
import com.github.antsrc.ant.lsp.AntSymbol
import com.github.antsrc.ant.lsp.AntUsageSearcher
import com.intellij.codeInsight.navigation.actions.GotoDeclarationOrUsageHandler2
import com.intellij.find.usages.api.PsiUsage
import com.intellij.find.usages.api.UsageSearchParameters
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.search.GlobalSearchScope
import com.github.antsrc.ant.settings.AntExecutable
import com.github.antsrc.ant.settings.AntSettings
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

        // The IDE restarts highlighting when diagnostics arrive, so one pass runs after they did.
        waitFor("diagnostics for main.ant") { events.contains("diagnostics main.ant") }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val infos = myFixture.doHighlighting()
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

        // The server offers inlay hints; the plugin shows none.
        val hints = File(dir, "hints.ant").apply { writeText("var count = 1\nprintln(count)\n") }
        val hintsFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(hints)!!
        myFixture.openFileInEditor(hintsFile)
        waitFor("diagnostics for hints.ant") { events.contains("diagnostics hints.ant") }
        myFixture.doHighlighting()
        assertEquals(emptyList<String>(), myFixture.editor.inlayModel.getInlineElementsInRange(0, myFixture.editor.document.textLength).map { it.renderer.toString() })

        // Navigation: a use of a function is a reference the IDE's symbol navigation resolves to
        // its declaration, which is what Cmd+hover underlines and Cmd+click follows.
        val nav = File(dir, "nav.ant").apply { writeText("func twice(n: int): int { n * 2 }\nvar four = twice(2)\n") }
        val navFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(nav)!!
        myFixture.openFileInEditor(navFile)
        waitFor("the client to open nav.ant") { events.contains("opened nav.ant") }
        waitFor("diagnostics for nav.ant") { events.contains("diagnostics nav.ant") }
        val useOffset = myFixture.editor.document.text.indexOf("twice(2)") + 1
        val declOffset = myFixture.editor.document.text.indexOf("twice(n") + 1
        // These run on a background thread in the IDE, and insist on it.
        fun <T> inBackground(compute: () -> T): T =
            ApplicationManager.getApplication().executeOnPooledThread<T> { ReadAction.compute<T, Throwable> { compute() } }.get(30, TimeUnit.SECONDS)
        val editor = myFixture.editor
        val psiFile = myFixture.file
        assertNotNull("Cmd+hover underlines the use of twice", inBackground { GotoDeclarationOrUsageHandler2.getCtrlMouseData(editor, psiFile, useOffset) })
        assertEquals("Cmd+click on the use goes to the declaration", "GTD", inBackground { GotoDeclarationOrUsageHandler2.testGTDUOutcome(editor, psiFile, useOffset)?.name })
        assertEquals("Cmd+click on the declaration shows usages", "SU", inBackground { GotoDeclarationOrUsageHandler2.testGTDUOutcome(editor, psiFile, declOffset)?.name })
        myFixture.editor.caretModel.moveToOffset(useOffset)
        myFixture.performEditorAction(IdeActions.ACTION_GOTO_DECLARATION)
        assertEquals("Go to Declaration lands on the declaration", declOffset - 1, myFixture.editor.caretModel.offset)
        val parameters = object : UsageSearchParameters {
            override fun getProject() = this@AntLspTest.project
            override fun areValid() = true
            override val target get() = AntSymbol(project, navFile, TextRange(declOffset - 1, declOffset + 4), "twice")
            override val searchScope get() = GlobalSearchScope.allScope(project)
        }
        val usages = inBackground { AntUsageSearcher().collectImmediateResults(parameters) }
        assertEquals("one usage, the call: $usages", listOf(TextRange(useOffset - 1, useOffset + 4)), usages.map { (it as PsiUsage).range })

        waitFor("the prelude source") { AntPrelude.getInstance(project).virtualFile != null }
        val prelude = AntPrelude.getInstance(project).virtualFile!!
        val uri = AntPrelude.getInstance(project).uri
        assertTrue("the prelude's URI: $uri", uri in AntPrelude.URIS)
        assertEquals(uri!!.substringAfterLast('/'), prelude.name)
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
