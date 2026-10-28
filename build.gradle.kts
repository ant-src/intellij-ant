import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.github.antsrc"
version = "0.1.0"

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
        intellijIdea("2025.3")
        testFramework(TestFrameworkType.Platform)
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
        name = "Ant"
        version = project.version.toString()
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map { readme ->
            val start = readme.indexOf("<!-- plugin description -->")
            val end = readme.indexOf("<!-- plugin description end -->")
            require(start >= 0 && end > start) { "README.md lacks the plugin description markers" }
            readme.substring(start + "<!-- plugin description -->".length, end).trim()
                .split("\n\n")
                .joinToString("\n") { "<p>" + it.replace("\n", " ") + "</p>" }
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
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
        }
    }
}

// The end-to-end tests start the real language server. ANT_PATH names the executable; without
// it, the sibling checkout of the ant repository is built, when there is one and go is installed.
val antRepo = layout.projectDirectory.dir("../ant")
val builtAnt = layout.buildDirectory.file("ant/ant")

val buildAnt by tasks.registering(Exec::class) {
    onlyIf { System.getenv("ANT_PATH").isNullOrBlank() && antRepo.asFile.isDirectory }
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
    }
}
