package com.github.antsrc.ant.lsp

import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture

/** `ant lsp` with its one request beyond the protocol (lsp.md section 4, Definition). */
interface AntLanguageServer : LanguageServer {
    /** Returns the text of the document with the given URI, `ant:/prelude.d.ant`. */
    @JsonRequest("ant/source")
    fun source(params: SourceParams): CompletableFuture<String>
}

class SourceParams(@JvmField val uri: String)
