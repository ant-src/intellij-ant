# Ant for IntelliJ IDEA

<!-- plugin description -->
Ant language support for IntelliJ IDEA and the other JetBrains IDEs: syntax highlighting, brace
matching, comments, indentation, and run configurations for `.ant` files, with the `ant` language
server behind completion, diagnostics, hover, navigation, rename, formatting, and more.

The plugin needs the `ant` command, installed with `go install github.com/ant-src/ant/cmd/ant@latest`,
and the LSP4IJ plugin, which it depends on and which the IDE installs with it.
<!-- plugin description end -->

## What it does

The plugin itself knows the language's lexical structure (`spec/ant-lang.md` sections 2 and 5):

- Syntax highlighting of keywords, literals, escapes, regular expressions, template strings with
  the code inside `${...}`, comments, and HTML expressions with the code inside `{...}`; the
  colors are under Settings | Editor | Color Scheme | Ant.
- Brace matching and auto-closing for `{}`, `()`, `[]`, `${}`, and `<tag>...</tag>`; Enter
  between them indents a line of its own; `}` outdents; two-space indentation as `ant fmt` writes.
- Line and block comments, quote handling, and the run icon in the gutter.
- Run configurations: Run 'file.ant' runs `ant run` with the file's arguments, working directory,
  and environment; a `path:line:col` in the output opens the file there.

Everything that needs to understand the program comes from `ant lsp`, through
[LSP4IJ](https://github.com/redhat-developer/lsp4ij): errors as you type, completion, hover with
types and doc comments, signature help, go to declaration (into the prelude too, as a read-only
document), find usages, highlight usages, rename, the structure view and breadcrumbs, go to symbol,
Reformat Code with `ant fmt`, folding, extend selection, semantic highlighting, inlay hints, and
quick fixes. `spec/lsp.md` in the [spec](https://github.com/ant-src/spec) is the contract.

## Setup

1. Install the plugin. It depends on LSP4IJ, which the IDE installs with it.
2. Install `ant` with `go install github.com/ant-src/ant/cmd/ant@latest`. The plugin looks for `ant`
   on PATH, then in `$GOBIN`, then in the `bin` directory of `go env GOPATH`, then in `~/go/bin`;
   a shell script named `ant`, such as Apache Ant's launcher, is passed over. Settings |
   Languages & Frameworks | Ant names the executable when it is somewhere else.
3. Open a `.ant` file. The server starts with the first one; Settings | Languages & Frameworks |
   Language Servers | Ant has its console and trace.

The plugin runs on IntelliJ IDEA 2024.3 and later, Community and Ultimate, and on the other
IntelliJ-based IDEs of the same versions.

## Development

```
./gradlew test          # the lexer, editing, run configuration, and language server tests
./gradlew runIde        # an IDE with the plugin
./gradlew buildPlugin   # build/distributions/intellij-ant-<version>.zip
./gradlew verifyPlugin  # compatibility with the IDEs named in build.gradle.kts
```

The language server test runs the real `ant lsp`: it uses the executable `ANT_PATH` names, or
builds the sibling checkout `../ant` when `go` is installed, and passes without checking anything
when there is neither. The lexer corpus test reads the `.ant` files of `ANT_REPO`, by default
`../ant`.

The highlighting lexer is written by hand in `src/main/kotlin/com/github/antsrc/ant/lexer`; it
reads the same tokens as `ant`'s lexer, from the spec, and recovers from anything. The PSI is flat:
the tokens under the file. Indentation comes from a formatting model over the brackets and tags;
Reformat Code goes to the server.

## License

MIT, see [LICENSE](LICENSE).
