package com.github.antsrc.ant.debug

import com.github.antsrc.ant.AntFileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider

/** A line breakpoint has no settings of its own. */
class AntBreakpointProperties : XBreakpointProperties<AntBreakpointProperties>() {
    override fun getState() = this
    override fun loadState(state: AntBreakpointProperties) {}
}

/** A breakpoint on a line of an Ant file, which ant dap binds to a stop point (spec/dap.md section 4). */
class AntLineBreakpointType : XLineBreakpointType<AntBreakpointProperties>(ID, "Ant Line Breakpoints") {
    override fun canPutAt(file: VirtualFile, line: Int, project: Project) =
        FileTypeRegistry.getInstance().isFileOfType(file, AntFileType)

    override fun createBreakpointProperties(file: VirtualFile, line: Int) = AntBreakpointProperties()

    /** The condition and the logged expression are Ant expressions (spec/dap.md section 6). */
    override fun getEditorsProvider(breakpoint: XLineBreakpoint<AntBreakpointProperties>, project: Project): XDebuggerEditorsProvider = AntEditorsProvider

    companion object {
        const val ID = "ant-line"
    }
}
