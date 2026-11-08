import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.github.antsrc"
version = "0.2.0"

// The oldest IDE the plugin supports, which it compiles against, and the newest the build checks it on.
val oldestIde = "2025.3"
val newestIde = "2026.3"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // The oldest platform the plugin supports, and the first with the IDE's LSP client
        // complete enough for Ant (signature help, structure view, selection range); the
        // verifier checks the newer ones below.
        intellijIdea(oldestIde)
        testFramework(TestFrameworkType.Platform)
    }
    // From 2026.3 the IDE keeps lsp4j's debug protocol in a library module that a plugin sees only
    // by declaring it, a module older IDEs lack; so the plugin ships it. Its jsonrpc stays the IDE's
    // (0.21 in 2025.3, 1.0 in 2026.3), so the bundled version is 2025.3's: newer ones call jsonrpc
    // methods older IDEs lack, and 0.21 runs on each IDE's jsonrpc up to 1.0.
    runtimeOnly("org.eclipse.lsp4j:org.eclipse.lsp4j.debug:0.21.0") {
        exclude(group = "org.eclipse.lsp4j", module = "org.eclipse.lsp4j.jsonrpc")
        exclude(group = "org.eclipse.lsp4j", module = "org.eclipse.lsp4j.generator")
        exclude(group = "com.google.code.gson")
    }
    testImplementation("junit:junit:4.13.2")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // 2025.3 bundles Kotlin 2.2, so the plugin's bytecode and standard library calls stay there.
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.github.antsrc.ant"
        name = "Ant Language"
        version = project.version.toString()
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map { readme ->
            val start = readme.indexOf("<!-- plugin description -->")
            val end = readme.indexOf("<!-- plugin description end -->")
            require(start >= 0 && end > start) { "README.md lacks the plugin description markers" }
            // The README's Markdown, as the HTML the Marketplace shows: paragraphs, links, and code.
            readme.substring(start + "<!-- plugin description -->".length, end).trim()
                .split("\n\n")
                .joinToString("\n") { "<p>" + it.replace("\n", " ") + "</p>" }
                .replace(Regex("""\[([^\]]+)]\(([^)]+)\)"""), "<a href=\"$2\">$1</a>")
                .replace(Regex("`([^`]+)`"), "<code>$1</code>")
        }
        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
        vendor {
            name = "ant-lang"
            url = "https://github.com/ant-src"
        }
    }
    // The publish workflow signs and uploads the plugin with these, from the repository's secrets.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, newestIde)
        }
    }
}

// The classes probe: a plugin, never shipped, whose ant-classes command loads every class the Ant
// plugin refers to through the plugin's own class loader, in a real IDE (see ClassesProbe). The
// tests cannot catch a class an installed plugin does not see, as they load it on one class path.
val probe = sourceSets.create("probe") {
    compileClasspath += sourceSets.main.get().compileClasspath
}
val probeJar = tasks.register<Jar>("probeJar") {
    // Its plugin.xml stays out of the source set's resources, where the Ant plugin's patched one goes.
    from(probe.output.classesDirs)
    from(layout.projectDirectory.dir("src/probe/plugin"))
    archiveBaseName = "ant-classes-probe"
}
val probeRuns = listOf(oldestIde, newestIde).map { ide ->
    intellijPlatformTesting.runIde.register("checkClassLoading-$ide") {
        type = IntelliJPlatformType.IntellijIdea
        version = ide
        // The probe goes into the sandbox's plugins beside the Ant plugin.
        prepareSandboxTask {
            from(probeJar) { into("ant-classes-probe/lib") }
        }
        task {
            args("ant-classes")
            jvmArgumentProviders += CommandLineArgumentProvider { listOf("-Djava.awt.headless=true") }
        }
    }
}
// Runs ant-classes in the oldest and the newest IDE the plugin supports; fails when a class does not load.
tasks.register("checkClassLoading") {
    group = "verification"
    description = "Loads every class the plugin refers to through its own class loader in real IDEs."
    dependsOn(probeRuns.map { run -> run.map { it.task } })
}

// The end-to-end tests start the real language server. ANT_PATH names the executable; without
// it, the sibling checkout of the ant repository is built, when there is one and go is installed.
val antRepo = layout.projectDirectory.dir("../ant")
val builtAnt = layout.buildDirectory.file("ant/ant")

val buildAnt by tasks.registering(Exec::class) {
    onlyIf {
        System.getenv("ANT_PATH").isNullOrBlank() && antRepo.asFile.isDirectory &&
            (System.getenv("PATH") ?: "").split(File.pathSeparator).any { File(it, "go").canExecute() || File(it, "go.exe").canExecute() }
    }
    workingDir(antRepo)
    commandLine("go", "build", "-o", builtAnt.get().asFile.absolutePath, "./cmd/ant")
    isIgnoreExitValue = true
}

tasks {
    test {
        useJUnit()
        dependsOn(buildAnt)
        environment("ANT_PATH", System.getenv("ANT_PATH")?.takeIf { it.isNotBlank() } ?: builtAnt.get().asFile.absolutePath)
        environment("ANT_REPO", System.getenv("ANT_REPO")?.takeIf { it.isNotBlank() } ?: antRepo.asFile.absolutePath)
        // A failure's message and trace in the build output, so that CI logs say what failed.
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
