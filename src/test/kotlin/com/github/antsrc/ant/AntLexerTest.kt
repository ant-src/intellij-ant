package com.github.antsrc.ant

import com.github.antsrc.ant.lexer.AntTokenTypes as T
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntLexerTest {
    @Test
    fun keywordsFieldsAndReservedWords() {
        assertEquals(T.KEYWORD, typeOf("var x = 1", "var"))
        assertEquals(T.KEYWORD, typeOf("if a { b } else { c }", "else"))
        assertEquals(T.LITERAL_KEYWORD, typeOf("x is null", "null"))
        assertEquals(T.KEYWORD, typeOf("x is void", "void"))
        assertEquals(T.IDENTIFIER, typeOf("event.type", "type"))
        assertEquals(T.IDENTIFIER, typeOf("event?.type", "type"))
        assertEquals(T.IDENTIFIER, typeOf("var t = { type: string }", "type"))
        assertEquals(T.IDENTIFIER, typeOf("Map{ class: \"a\" }", "class"))
        assertEquals(T.IDENTIFIER, typeOf("f(type: 1)", "type"))
        assertEquals(T.RESERVED, typeOf("let x = 1", "let"))
        assertEquals(T.RESERVED, typeOf("x = await y", "await"))
        assertEquals(T.IDENTIFIER, typeOf("var value = Value", "Value"))
        assertEquals(T.IDENTIFIER, typeOf("var _ = 1", "_"))
    }

    @Test
    fun comments() {
        assertEquals("LINE_COMMENT(// hi) KEYWORD(var) IDENTIFIER(x)", shape("// hi\nvar x"))
        assertEquals(T.MODULE_DOC_COMMENT, typeOf("//! Geometry has points.", "//! Geometry has points."))
        assertEquals(T.BLOCK_COMMENT, typeOf("var x = /* a\nb */ 1", "/* a\nb */"))
        assertEquals("BLOCK_COMMENT(/* open)", shape("/* open"))
        assertEquals(T.SHEBANG, typeOf("#!/usr/bin/env ant\nvar x", "#!/usr/bin/env ant"))
        assertEquals(T.BAD_CHARACTER, typeOf("var x\n#!/no", "#"))
    }

    @Test
    fun numbers() {
        assertEquals(T.INT, typeOf("var n = 1_000_000", "1_000_000"))
        assertEquals(T.INT, typeOf("var n = 0xFF", "0xFF"))
        assertEquals(T.INT, typeOf("var n = 0b1010", "0b1010"))
        assertEquals(T.FLOAT, typeOf("var n = 2.5e-3", "2.5e-3"))
        assertEquals(T.FLOAT, typeOf("var n = 1e9", "1e9"))
        assertEquals(T.FLOAT, typeOf("var n = 3.14", "3.14"))
        assertEquals("INT(1) DOT(.) IDENTIFIER(x)", shape("1.x"))
        assertEquals("IDENTIFIER(a) DOT(.) INT(5)", shape("a.5"))
    }

    @Test
    fun stringsAndEscapes() {
        assertEquals("STRING(\"a\\nb\")", shape("\"a\\nb\""))
        val toks = lex("var s = \"a\\nb\\qc\\u{1F600}\\u{D800}\"", escapes = true).filter { it.type != T.WHITE_SPACE }
        assertEquals(
            "KEYWORD(var) IDENTIFIER(s) OPERATOR(=) STRING(\"a) VALID_ESCAPE(\\n) STRING(b) INVALID_ESCAPE(\\q) STRING(c) VALID_ESCAPE(\\u{1F600}) INVALID_ESCAPE(\\u{D800}) STRING(\")",
            toks.joinToString(" "),
        )
        // an unterminated string ends with its line
        assertEquals("STRING(\"abc) KEYWORD(var) IDENTIFIER(y)", shape("\"abc\nvar y"))
        assertEquals("STRING(\"a\\\"b\")", shape("\"a\\\"b\""))
    }

    @Test
    fun templateStrings() {
        assertEquals(
            "TEMPLATE_START(`) TEMPLATE_TEXT(total: ) INTERPOLATION_START(\${) IDENTIFIER(count) INTERPOLATION_END(}) TEMPLATE_TEXT( items) TEMPLATE_END(`)",
            shape("`total: \${count} items`"),
        )
        // braces nest inside an interpolation, and the template is an operand
        assertEquals(
            "KEYWORD(var) IDENTIFIER(s) OPERATOR(=) TEMPLATE_START(`) INTERPOLATION_START(\${) IDENTIFIER(xs) PIPE(|) IDENTIFIER(Array) DOT(.) IDENTIFIER(map) LPAREN(() LBRACE({) IDENTIFIER(x) RBRACE(}) RPAREN()) INTERPOLATION_END(}) TEMPLATE_TEXT( done) TEMPLATE_END(`) OPERATOR(/) INT(2)",
            shape("var s = `\${xs | Array.map({ x })} done` / 2"),
        )
        // a template spans lines and keeps escapes in its text
        val toks = lex("`a\\`b\n\${1}`", escapes = true)
        assertEquals("TEMPLATE_START(`) TEMPLATE_TEXT(a) VALID_ESCAPE(\\`) TEMPLATE_TEXT(b\\n) INTERPOLATION_START(\${) INT(1) INTERPOLATION_END(}) TEMPLATE_END(`)", toks.joinToString(" "))
        assertTrue(endsBalanced("var s = `a \${f({ a: 1 })} b`"))
        assertFalse(endsBalanced("var s = `open"))
    }

    @Test
    fun regularExpressionsAndDivision() {
        assertEquals(T.REGEX, typeOf("var r = /^\\d+$/i", "/^\\d+$/i"))
        assertEquals(T.REGEX, typeOf("Regex.test(/ant/, s)", "/ant/"))
        assertEquals(T.REGEX, typeOf("return /x/", "/x/"))
        assertEquals("KEYWORD(var) IDENTIFIER(q) OPERATOR(=) IDENTIFIER(total) OPERATOR(/) IDENTIFIER(count)", shape("var q = total / count"))
        assertEquals("KEYWORD(var) IDENTIFIER(r) OPERATOR(=) LPAREN(() IDENTIFIER(total) OPERATOR(/) IDENTIFIER(count) RPAREN())", shape("var r = (total\n  / count)"))
        assertEquals("IDENTIFIER(x) OPERATOR(/=) INT(2)", shape("x /= 2"))
        // a / inside a class or escaped does not end the literal
        assertEquals(T.REGEX, typeOf("f(/[/]a\\/b/)", "/[/]a\\/b/"))
        // inside \Q...\E only an escaped / does not end the literal (section 2, Literals)
        assertEquals(T.REGEX, typeOf("f(/\\Qa\\/b\\E/)", "/\\Qa\\/b\\E/"))
        assertEquals("REGEX(/\\Qa/) BAD_CHARACTER(\\) IDENTIFIER(E) OPERATOR(/)", shape("/\\Qa/\\E/"))
        // an unclosed regular expression ends with its line
        assertEquals("KEYWORD(var) IDENTIFIER(r) OPERATOR(=) REGEX(/abc) KEYWORD(var) IDENTIFIER(y) OPERATOR(=) INT(1)", shape("var r = /abc\nvar y = 1"))
        // after a line break the operand before it still decides
        assertEquals("IDENTIFIER(a) OPERATOR(/) IDENTIFIER(b) OPERATOR(/)", shape("a\n/ b /"))
        assertEquals("IDENTIFIER(a) SEMICOLON(;) REGEX(/b/)", shape("a; /b/"))
        assertEquals("RBRACE(}) OPERATOR(/) INT(2)", shape("} / 2"))
    }

    @Test
    fun markupVersusComparison() {
        assertEquals("KEYWORD(var) IDENTIFIER(ok) OPERATOR(=) IDENTIFIER(n) OPERATOR(<) IDENTIFIER(limit)", shape("var ok = n < limit"))
        assertEquals("KEYWORD(var) IDENTIFIER(ok) OPERATOR(=) IDENTIFIER(n) OPERATOR(<) IDENTIFIER(limit)", shape("var ok = n <limit"))
        assertEquals("KEYWORD(var) IDENTIFIER(ok) OPERATOR(=) IDENTIFIER(n) OPERATOR(<=) IDENTIFIER(limit)", shape("var ok = n <= limit"))
        assertEquals(
            "KEYWORD(var) IDENTIFIER(h) OPERATOR(=) TAG_OPEN(<) TAG_NAME(div) ATTR_NAME(class) ATTR_EQ(=) ATTR_VALUE(\"a\") TAG_CLOSE(>) MARKUP_TEXT(hi) END_TAG_OPEN(</) TAG_NAME(div) TAG_CLOSE(>)",
            shape("var h = <div class=\"a\">hi</div>"),
        )
        // the first token of a line in a block begins markup even after an operand
        assertEquals(
            "KEYWORD(func) IDENTIFIER(f) LPAREN(() RPAREN()) LBRACE({) IDENTIFIER(a) TAG_OPEN(<) TAG_NAME(p) TAG_CLOSE(>) MARKUP_TEXT(x) END_TAG_OPEN(</) TAG_NAME(p) TAG_CLOSE(>) RBRACE(})",
            shape("func f() {\n  a\n  <p>x</p>\n}"),
        )
        // but not inside parentheses, where line breaks end nothing
        assertEquals("LPAREN(() IDENTIFIER(total) OPERATOR(<) IDENTIFIER(limit) RPAREN())", shape("(total\n  <limit)"))
        // a line that continues the statement is not a line start
        assertEquals("IDENTIFIER(a) OPERATOR(+) IDENTIFIER(b) OPERATOR(<) IDENTIFIER(c)", shape("a +\n  b <c"))
        // text is not code: a keyword in text stays text
        assertEquals("TAG_OPEN(<) TAG_NAME(p) TAG_CLOSE(>) MARKUP_TEXT(for the record) END_TAG_OPEN(</) TAG_NAME(p) TAG_CLOSE(>)", shape("<p>for the record</p>"))
        // an interpolation holds code, and nested markup inside it closes in order
        assertEquals(
            "KEYWORD(var) IDENTIFIER(h) OPERATOR(=) TAG_OPEN(<) TAG_NAME(p) TAG_CLOSE(>) MARKUP_EXPR_START({) KEYWORD(if) IDENTIFIER(x) LBRACE({) STRING(\"a\") RBRACE(}) MARKUP_EXPR_END(}) END_TAG_OPEN(</) TAG_NAME(p) TAG_CLOSE(>)",
            shape("var h = <p>{if x { \"a\" }}</p>"),
        )
        val nested = shape("var h = <p>{Array.map(xs, { <b>{x}</b> })} tail</p>\nvar y = 1")
        assertTrue(nested, nested.endsWith("MARKUP_EXPR_END(}) MARKUP_TEXT( tail) END_TAG_OPEN(</) TAG_NAME(p) TAG_CLOSE(>) KEYWORD(var) IDENTIFIER(y) OPERATOR(=) INT(1)"))
        // nested elements of one name close in order, and a self-closing one ends at />
        val same = shape("var h = <div><div>a</div><br /></div>\nvar x = 1")
        assertTrue(same, same.endsWith("TAG_OPEN(<) TAG_NAME(br) TAG_SELF_CLOSE(/>) END_TAG_OPEN(</) TAG_NAME(div) TAG_CLOSE(>) KEYWORD(var) IDENTIFIER(x) OPERATOR(=) INT(1)"))
        // a fragment
        assertEquals(
            "KEYWORD(func) IDENTIFIER(f) LPAREN(() RPAREN()) COLON(:) IDENTIFIER(Html) LBRACE({) TAG_OPEN(<) TAG_CLOSE(>) TAG_OPEN(<) TAG_NAME(li) TAG_CLOSE(>) MARKUP_TEXT(a) END_TAG_OPEN(</) TAG_NAME(li) TAG_CLOSE(>) END_TAG_OPEN(</) TAG_CLOSE(>) RBRACE(}) KEYWORD(var) IDENTIFIER(y) OPERATOR(=) INT(2)",
            shape("func f(): Html {\n  <>\n    <li>a</li>\n  </>\n}\nvar y = 2"),
        )
        // markup is an operand: a / after it divides
        assertEquals("TAG_OPEN(<) TAG_NAME(br) TAG_SELF_CLOSE(/>) OPERATOR(/) INT(2)", shape("<br /> / 2"))
    }

    @Test
    fun markupDetails() {
        // attributes: text, expression, bare, spread; references in text
        assertEquals(
            "TAG_OPEN(<) TAG_NAME(input) ATTR_NAME(type) ATTR_EQ(=) ATTR_VALUE(\"checkbox\") ATTR_NAME(checked) ATTR_EQ(=) MARKUP_EXPR_START({) IDENTIFIER(t) DOT(.) IDENTIFIER(done) MARKUP_EXPR_END(}) ATTR_NAME(disabled) MARKUP_EXPR_START({) ELLIPSIS(...) IDENTIFIER(attrs) MARKUP_EXPR_END(}) TAG_SELF_CLOSE(/>)",
            shape("<input type=\"checkbox\" checked={t.done} disabled {...attrs} />"),
        )
        assertEquals(
            "TAG_OPEN(<) TAG_NAME(p) TAG_CLOSE(>) MARKUP_TEXT(a ) MARKUP_ENTITY(&amp;) MARKUP_TEXT( b & c ) MARKUP_ENTITY(&#123;) MARKUP_ENTITY(&#x7D;) END_TAG_OPEN(</) TAG_NAME(p) TAG_CLOSE(>)",
            shape("<p>a &amp; b & c &#123;&#x7D;</p>"),
        )
        // script and style hold raw text up to their end tag, whatever is inside
        assertEquals(
            "TAG_OPEN(<) TAG_NAME(script) TAG_CLOSE(>) MARKUP_TEXT(if (a < b) { x = `\${y}`; }) END_TAG_OPEN(</) TAG_NAME(script) TAG_CLOSE(>)",
            shape("<script>if (a < b) { x = `\${y}`; }</script>").replace("RAW_TEXT", "MARKUP_TEXT"),
        )
        assertEquals(T.RAW_TEXT, typeOf("<style>a { b: c }</style>", "a { b: c }"))
        assertEquals(T.RAW_TEXT, typeOf("<SCRIPT>x</script >", "x"))
        // a stray } or < in text, and <!-- are bad characters, not the end of the markup
        assertEquals("TAG_OPEN(<) TAG_NAME(p) TAG_CLOSE(>) BAD_CHARACTER(}) MARKUP_TEXT(a) BAD_CHARACTER(<) MARKUP_TEXT(!-- c -->) END_TAG_OPEN(</) TAG_NAME(p) TAG_CLOSE(>)", shape("<p>}a<!-- c --></p>"))
        // white space alone between tags is white space; with text it is text
        assertEquals("TAG_OPEN(<) TAG_NAME(ul) TAG_CLOSE(>) TAG_OPEN(<) TAG_NAME(li) TAG_CLOSE(>) MARKUP_TEXT( a ) END_TAG_OPEN(</) TAG_NAME(li) TAG_CLOSE(>) END_TAG_OPEN(</) TAG_NAME(ul) TAG_CLOSE(>)", shape("<ul>\n  <li> a </li>\n</ul>"))
        // an unterminated start tag: the next tag is content, and an end tag without > still closes
        assertTrue(endsBalanced("var h = <div><p>x</p></div>"))
        assertFalse(endsBalanced("var h = <div>open"))
        assertEquals("TAG_OPEN(<) TAG_NAME(div) TAG_OPEN(<) TAG_NAME(p) TAG_CLOSE(>) MARKUP_TEXT(x) END_TAG_OPEN(</) TAG_NAME(p) TAG_CLOSE(>)", shape("<div\n<p>x</p>"))
        assertEquals("TAG_OPEN(<) TAG_NAME(p) TAG_CLOSE(>) MARKUP_TEXT(x) END_TAG_OPEN(</) TAG_NAME(p) KEYWORD(var)", shape("<p>x</p\nvar"))
    }

    @Test
    fun lineEndings() {
        // a line break after an operand ends the statement, so a <tag on the next line is markup
        assertEquals("IDENTIFIER(a) OPERATOR(=) IDENTIFIER(b) TAG_OPEN(<) TAG_NAME(c)", shape("a =\nb\n<c"))
        // and a < followed by a space is a comparison, line start or not
        assertEquals("IDENTIFIER(a) OPERATOR(=) IDENTIFIER(b) OPERATOR(<) IDENTIFIER(c)", shape("a = b\n< c"))
        // a leading . | or else continues the line too
        assertEquals("IDENTIFIER(a) DOT(.) IDENTIFIER(b) OPERATOR(<) IDENTIFIER(c)", shape("a\n  .b <c"))
        assertEquals("IDENTIFIER(xs) PIPE(|) IDENTIFIER(f) LPAREN(() RPAREN())", shape("xs\n  | f()"))
        // return ends its statement, so < on the next line in a block begins markup
        assertEquals("LBRACE({) KEYWORD(return) TAG_OPEN(<) TAG_NAME(p) TAG_CLOSE(>) END_TAG_OPEN(</) TAG_NAME(p) TAG_CLOSE(>) RBRACE(})", shape("{\n  return\n  <p></p>\n}"))
        // a comment between does not matter
        assertEquals("IDENTIFIER(a) LINE_COMMENT(// c) TAG_OPEN(<) TAG_NAME(p) TAG_SELF_CLOSE(/>)", shape("a // c\n<p/>"))
    }

    @Test
    fun badCharactersAndUnicode() {
        assertEquals("KEYWORD(var) IDENTIFIER(x) OPERATOR(=) BAD_CHARACTER(@) INT(1)", shape("var x = @1"))
        assertEquals("BAD_CHARACTER(€) IDENTIFIER(a)", shape("€a"))
        assertEquals("BAD_CHARACTER(😀) IDENTIFIER(a)", shape("😀a"))
        assertEquals("STRING(\"😀\") TAG_OPEN(<) TAG_NAME(p) TAG_CLOSE(>) MARKUP_TEXT(😀) END_TAG_OPEN(</) TAG_NAME(p) TAG_CLOSE(>)", shape("\"😀\"\n<p>😀</p>"))
    }

    @Test
    fun stateIsZeroOnlyAtTheStart() {
        val lexer = com.github.antsrc.ant.lexer.AntLexer()
        lexer.start("var x = `a \${b} c`\n<p>{x}</p>\n")
        val states = ArrayList<Int>()
        while (lexer.tokenType != null) {
            states.add(lexer.state)
            lexer.advance()
        }
        // the state is the one before each token: 0 at the start, and after that inside something or after a token
        assertEquals(0, states.first())
        assertTrue(states.toString(), states.drop(1).none { it == 0 })
        // the same text lexes to the same states
        lexer.start("var x = `a \${b} c`\n<p>{x}</p>\n")
        val again = ArrayList<Int>()
        while (lexer.tokenType != null) {
            again.add(lexer.state)
            lexer.advance()
        }
        assertEquals(states, again)
    }
}
