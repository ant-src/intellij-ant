package com.github.antsrc.ant.settings

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Finds the `ant` executable: the one the settings name, then `ant` on PATH, then in `$GOBIN`,
 * then in the `bin` directory of `go env GOPATH`, then in `~/go/bin`. A hit is kept until the
 * setting changes; a miss is not, so that an `ant` installed later is found. A script named
 * `ant`, such as Apache Ant's launcher, is passed over: the Ant command is a compiled program.
 */
object AntExecutable {
    @Volatile private var cached: Path? = null

    fun find(): Path? {
        cached?.let { return it }
        val found = locate()
        cached = found
        return found
    }

    fun forget() {
        cached = null
    }

    /** The command to run, for messages and command lines: the executable found, or plain `ant`. */
    fun command(): String = find()?.toString() ?: "ant"

    private fun locate(): Path? {
        val configured = AntSettings.getInstance().antPath
        if (configured.isNotBlank()) return Path.of(configured)
        val exe = if (SystemInfo.isWindows) "ant.exe" else "ant"
        PathEnvironmentVariableUtil.findAllExeFilesInPath(exe).map { it.toPath() }.firstOrNull { isAnt(it) }?.let { return it }
        System.getenv("GOBIN")?.takeIf { it.isNotBlank() }?.let { Path.of(it, exe) }?.takeIf { isAnt(it) }?.let { return it }
        goPath()?.let { Path.of(it, "bin", exe) }?.takeIf { isAnt(it) }?.let { return it }
        val home = Path.of(System.getProperty("user.home"), "go", "bin", exe)
        return home.takeIf { isAnt(it) }
    }

    private fun goPath(): String? = try {
        val output = ExecUtil.execAndGetOutput(GeneralCommandLine("go", "env", "GOPATH"), 5000)
        output.stdout.trim().takeIf { output.exitCode == 0 && it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    /** Whether file is an executable program, and not a shell script that happens to be called ant. */
    fun isAnt(file: Path): Boolean {
        if (!Files.isRegularFile(file) || !Files.isExecutable(file)) return false
        return try {
            Files.newInputStream(file).use { it.read() != '#'.code || it.read() != '!'.code }
        } catch (e: Exception) {
            false
        }
    }

    fun isAnt(file: File): Boolean = isAnt(file.toPath())
}
