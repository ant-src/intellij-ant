package com.github.antsrc.ant.debug

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.ui.XBreakpointCustomPropertiesPanel
import javax.swing.JComponent

/** Whether the panic breakpoint stops at a panic Panic.catch will stop too. */
class AntPanicProperties : XBreakpointProperties<AntPanicProperties>() {
    @Attribute("caught")
    var caught = false

    override fun getState() = this
    override fun loadState(state: AntPanicProperties) {
        caught = state.caught
    }
}

/**
 * The panic filters of ant dap (spec/dap.md section 5) as one breakpoint in the Breakpoints
 * dialog: enabled, it stops at a panic nothing catches, and with "Caught panics too", at every one.
 */
class AntPanicBreakpointType : XBreakpointType<XBreakpoint<AntPanicProperties>, AntPanicProperties>(ID, "Ant Panics") {
    override fun getDisplayText(breakpoint: XBreakpoint<AntPanicProperties>) =
        if (breakpoint.properties.caught) "Every Ant panic" else "Ant panics nothing catches"

    override fun createProperties() = AntPanicProperties()

    override fun createDefaultBreakpoint(creator: XBreakpointCreator<AntPanicProperties>): XBreakpoint<AntPanicProperties> =
        creator.createBreakpoint(AntPanicProperties())

    override fun createCustomPropertiesPanel(project: Project): XBreakpointCustomPropertiesPanel<XBreakpoint<AntPanicProperties>> =
        object : XBreakpointCustomPropertiesPanel<XBreakpoint<AntPanicProperties>>() {
            private val caught = JBCheckBox("Caught panics too, which Panic.catch turns into values")
            override fun getComponent(): JComponent = caught
            override fun loadFrom(breakpoint: XBreakpoint<AntPanicProperties>) {
                caught.isSelected = breakpoint.properties.caught
            }
            override fun saveTo(breakpoint: XBreakpoint<AntPanicProperties>) {
                breakpoint.properties.caught = caught.isSelected
            }
        }

    companion object {
        const val ID = "ant-panic"
    }
}
