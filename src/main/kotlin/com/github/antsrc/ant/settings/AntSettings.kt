package com.github.antsrc.ant.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/** The plugin's settings: where the `ant` executable is. */
@Service(Service.Level.APP)
@State(name = "AntSettings", storages = [Storage("ant.xml")])
class AntSettings : SimplePersistentStateComponent<AntSettings.State>(State()) {
    class State : BaseState() {
        /** The `ant` executable; empty means to look for it (see [AntExecutable]). */
        var antPath by string("")
        /** Inlay hints from the server (lsp.md section 4): the inferred type after a `var`, and the parameter name before an argument. */
        var typeHints by property(true)
        var parameterHints by property(true)
    }

    var antPath: String
        get() = state.antPath ?: ""
        set(value) {
            state.antPath = value
            AntExecutable.forget()
        }

    var typeHints: Boolean
        get() = state.typeHints
        set(value) { state.typeHints = value }
    var parameterHints: Boolean
        get() = state.parameterHints
        set(value) { state.parameterHints = value }

    companion object {
        fun getInstance(): AntSettings = ApplicationManager.getApplication().getService(AntSettings::class.java)
    }
}
