package com.github.antsrc.ant.probe

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.extensions.PluginId
import java.io.DataInputStream
import java.util.zip.ZipFile
import kotlin.io.path.listDirectoryEntries
import kotlin.system.exitProcess

/**
 * `ant-classes`: loads every class the Ant plugin's own classes refer to through the plugin's
 * class loader, in a real IDE, and exits with 1 when one of them does not load. The tests cannot
 * see this: they put the plugin on the IDE's one class path, where every library of the IDE is at
 * hand; an installed plugin sees only the IDE's core, the modules of the plugins it depends on,
 * and its own jars. So a library the IDE moves out of its core (as 2026.3 did with lsp4j's debug
 * protocol) breaks the installed plugin alone, when the class that needs it first loads.
 */
class ClassesProbe : ApplicationStarter {
    override val isHeadless: Boolean get() = true
    override val requiredModality: Int get() = ApplicationStarter.NOT_IN_EDT

    override fun main(args: List<String>) {
        val build = ApplicationInfo.getInstance().build.asString()
        val plugin = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
        if (plugin == null || !plugin.isEnabled) {
            println("ant-classes: $PLUGIN_ID is not loaded in $build")
            exitProcess(1)
        }
        val loader = plugin.pluginClassLoader ?: plugin.javaClass.classLoader
        // Each class the plugin refers to, with one of its own classes that refers to it.
        val references = sortedMapOf<String, String>()
        var own = 0
        for (jar in plugin.pluginPath.resolve("lib").listDirectoryEntries("*.jar")) {
            ZipFile(jar.toFile()).use { zip ->
                for (entry in zip.entries()) {
                    if (!entry.name.startsWith(OWN_PACKAGE) || !entry.name.endsWith(".class")) continue
                    own++
                    val name = entry.name.removeSuffix(".class").replace('/', '.')
                    references.putIfAbsent(name, name)
                    for (ref in referencedClasses(zip.getInputStream(entry).readBytes())) references.putIfAbsent(ref, name)
                }
            }
        }
        val missing = references.mapNotNull { (name, from) ->
            try {
                Class.forName(name, false, loader)
                null
            } catch (e: Throwable) {
                "$name (from $from): $e"
            }
        }
        if (own == 0) {
            println("ant-classes: no classes of $PLUGIN_ID under ${plugin.pluginPath}")
            exitProcess(1)
        }
        missing.forEach { println("ant-classes: cannot load $it") }
        println("ant-classes: $build: ${references.size - missing.size} of ${references.size} classes the $own classes of $PLUGIN_ID refer to load")
        exitProcess(if (missing.isEmpty()) 0 else 1)
    }

    companion object {
        private const val PLUGIN_ID = "com.github.antsrc.ant"
        private const val OWN_PACKAGE = "com/github/antsrc/ant/"
        private val DESCRIPTOR_CLASS = Regex("""L([\w$]+(?:/[\w$]+)+);""")

        /**
         * The classes a class file names for the JVM: its class constants, and the types in the
         * descriptors of the members it declares and of those it uses. Not the strings of Kotlin's
         * metadata, which name Kotlin's types (kotlin.Function1), not the JVM's.
         */
        fun referencedClasses(bytes: ByteArray): Set<String> {
            val input = DataInputStream(bytes.inputStream())
            input.skipBytes(8) // magic, minor and major version
            val count = input.readUnsignedShort()
            val utf8 = arrayOfNulls<String>(count)
            val classes = mutableListOf<Int>()
            val descriptors = mutableListOf<Int>()
            var i = 1
            while (i < count) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> utf8[i] = input.readUTF()
                    7 -> classes += input.readUnsignedShort()
                    16 -> descriptors += input.readUnsignedShort() // a method type
                    8, 19, 20 -> input.skipBytes(2)
                    15 -> input.skipBytes(3)
                    12 -> { input.skipBytes(2); descriptors += input.readUnsignedShort() } // a name and type
                    3, 4, 9, 10, 11, 17, 18 -> input.skipBytes(4)
                    5, 6 -> { input.skipBytes(8); i++ } // a long or a double takes two entries
                    else -> error("constant pool tag $tag")
                }
                i++
            }
            input.skipBytes(6) // access flags, this class, superclass (a class constant)
            input.skipBytes(2 * input.readUnsignedShort()) // interfaces (class constants)
            repeat(2) { // fields, then methods
                repeat(input.readUnsignedShort()) {
                    input.skipBytes(4) // access flags, name
                    descriptors += input.readUnsignedShort()
                    repeat(input.readUnsignedShort()) {
                        input.skipBytes(2)
                        input.skipBytes(input.readInt())
                    }
                }
            }
            val names = mutableSetOf<String>()
            for (index in classes) {
                val name = utf8[index] ?: continue
                if (name.startsWith("[")) DESCRIPTOR_CLASS.findAll(name).forEach { names += it.groupValues[1] } else names += name
            }
            for (index in descriptors) DESCRIPTOR_CLASS.findAll(utf8[index] ?: continue).forEach { names += it.groupValues[1] }
            return names.mapTo(sortedSetOf()) { it.replace('/', '.') }
        }
    }
}
