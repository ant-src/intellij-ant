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
 * setting changes; a miss only briefly, so that the search, which runs `go env`, is not repeated
 * for every file opened, while an `ant` installed later is still found. A script named `ant`,
 * such as Apache Ant's launcher, is passed over: the Ant command is a compiled program.
 */
object AntExecutable {
    @Volatile private var cached: Path? = null
    @Volatile private var missedAt = 0L

    fun find(): Path? {
        cached?.let { return it }
        if (System.currentTimeMillis() - missedAt < MISS_TTL_MS) return null
        val found = locate()
        cached = found
        if (found == null) missedAt = System.currentTimeMillis()
        return found
    }

    fun forget() {
        cached = null
        missedAt = 0L
    }

    /** What to tell a user who has no `ant`. */
    const val NOT_FOUND = "The ant executable was not found. Set its path in Settings | Languages & Frameworks | Ant, or install it with: go install github.com/ant-src/ant/cmd/ant@latest"
    private const val MISS_TTL_MS = 30_000L

    private fun locate(): Path? {
        val configured = AntSettings.getInstance().antPath
        // The setting is taken as it is, script or not, as long as the file exists.
        if (configured.isNotBlank()) return Path.of(configured).takeIf { Files.isRegularFile(it) }
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
