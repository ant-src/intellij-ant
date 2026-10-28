# Ant for IntelliJ IDEA

<!-- plugin description -->
Support for the [Ant](https://github.com/ant-src/ant) programming language: highlighting, run
configurations, and the language features of `ant lsp` through the IDE's own LSP client, including
diagnostics, completion, hover, go to definition, references, and formatting.
<!-- plugin description end -->

Needs IntelliJ IDEA 2025.3 or later (not the open-source builds or Android Studio, which lack the
LSP client). Rename arrives with 2026.1.

## Install ant

The plugin does not bundle `ant`:

```sh
go install github.com/ant-src/ant/cmd/ant@latest
```

It finds `ant` on `PATH` or in your Go bin directory. Settings | Languages & Frameworks | Ant
names another one.

## Run

Run 'file.ant' from the gutter, the editor, or the project view runs `ant run` with the file.

## Development

```sh
./gradlew test          # lexer, editing, run configuration, and language server tests; needs ../ant or ANT_PATH
./gradlew runIde        # an IDE with the plugin
./gradlew buildPlugin   # build/distributions/intellij-ant-<version>.zip
./gradlew verifyPlugin  # compatibility with the IDEs in build.gradle.kts
```
