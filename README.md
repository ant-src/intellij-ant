# Ant for IntelliJ IDEA

<!-- plugin description -->

Support for the [Ant](https://github.com/ant-src/ant) programming language: highlighting, run
configurations, tests with `ant test`, debugging through `ant dap`, and the language features of
`ant lsp` through the IDE's own LSP client, including diagnostics, completion, hover, go to
definition, references, and formatting.
<!-- plugin description end -->

Needs IntelliJ IDEA 2025.3 or later (not the open-source builds or Android Studio, which lack the
LSP client). Rename arrives with 2026.1.

## Install ant

The plugin does not bundle `ant`:

```sh
go install github.com/ant-src/ant/cmd/ant@latest
```

It finds `ant` on `PATH` or in your Go bin directory. Settings | Languages & Frameworks | Ant names
another one.

## Run

Run 'file.ant' from the gutter, the editor, or the project view runs `ant run` with the file. The
run configuration can name a file whose contents are the program's standard input; without one, the
program reads the console under Run and sees end of file under the debugger.

## Test

In a test file, whose name ends in `_test.ant`, `test` is highlighted as a keyword, and the gutter
shows a run icon on each test: Run or Debug there runs that test alone, with `ant test -run` or
under `ant dap`. On a subtest whose name interpolates, as the cases of a table do, it runs every
case; and each row of the table gets one of its own, which runs that case alone, when the table is a
`var` holding an array of record literals, the subtest is right inside a `for` over it, and the name
interpolates fields of the row whose values are literals. Run from the editor runs the test around
the caret, or all the file's tests outside one. An Ant run configuration on a test file runs `ant
test -json` and shows the results in the test tree, subtests under their tests; the gutter icon then
shows how each test last went, and for a table the worst of its cases. Its Tests to run field is a
`-run` pattern, and its program arguments are flags of `ant test`, such as `-failfast`. IDEs whose
LSP client shows code lenses (2026.2 does, 2025.3 does not) also show the run test and debug test
lenses of `ant lsp`, which do the same.

## Debug

Debug an Ant run configuration to run it under `ant dap`: line breakpoints with conditions and
logged expressions, stepping, pause, every goroutine's stack and variables, Set Value, Evaluate
Expression, watches, and values on hover for names and fields. A breakpoint that suspends logs its
expression in the console when it stops; one set not to suspend logs it as the program runs on.
**Ant Panics** in the Breakpoints dialog stops at a panic nothing catches, or with "Caught panics
too" at every panic, and the console shows the panic's trace. Stop interrupts the program as Ctrl-C
does under `ant run`, and ends the session when the program has not ended a few seconds later; Kill
Process ends it at once.

## Development

```sh
./gradlew test          # lexer, editing, run configuration, debugger, and language server tests; needs ../ant or ANT_PATH
./gradlew runIde        # an IDE with the plugin
./gradlew buildPlugin   # build/distributions/intellij-ant-<version>.zip
./gradlew verifyPlugin  # compatibility with the IDEs in build.gradle.kts
```
