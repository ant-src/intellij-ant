package com.github.antsrc.ant.highlight

import com.github.antsrc.ant.AntIcons
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage

class AntColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName() = "Ant"
    override fun getIcon() = AntIcons.FILE
    override fun getHighlighter(): SyntaxHighlighter = AntSyntaxHighlighter()
    override fun getAttributeDescriptors() = DESCRIPTORS
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getAdditionalHighlightingTagToDescriptorMap() = TAGS
    override fun getDemoText() = DEMO

    companion object {
        private val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Keyword", AntColors.KEYWORD),
            AttributesDescriptor("Reserved word", AntColors.RESERVED_WORD),
            AttributesDescriptor("Identifier", AntColors.IDENTIFIER),
            AttributesDescriptor("Number", AntColors.NUMBER),
            AttributesDescriptor("String", AntColors.STRING),
            AttributesDescriptor("String//Valid escape", AntColors.VALID_ESCAPE),
            AttributesDescriptor("String//Invalid escape", AntColors.INVALID_ESCAPE),
            AttributesDescriptor("String//Interpolation braces", AntColors.INTERPOLATION),
            AttributesDescriptor("Regular expression", AntColors.REGEX),
            AttributesDescriptor("Comments//Line comment", AntColors.LINE_COMMENT),
            AttributesDescriptor("Comments//Block comment", AntColors.BLOCK_COMMENT),
            AttributesDescriptor("Comments//Module doc", AntColors.DOC_COMMENT),
            AttributesDescriptor("Punctuation//Operator", AntColors.OPERATOR),
            AttributesDescriptor("Punctuation//Pipe", AntColors.PIPE),
            AttributesDescriptor("Punctuation//Braces", AntColors.BRACES),
            AttributesDescriptor("Punctuation//Parentheses", AntColors.PARENTHESES),
            AttributesDescriptor("Punctuation//Brackets", AntColors.BRACKETS),
            AttributesDescriptor("Punctuation//Comma", AntColors.COMMA),
            AttributesDescriptor("Punctuation//Dot", AntColors.DOT),
            AttributesDescriptor("Punctuation//Semicolon", AntColors.SEMICOLON),
            AttributesDescriptor("Bad character", AntColors.BAD_CHARACTER),
            AttributesDescriptor("Markup//Tag", AntColors.MARKUP_TAG),
            AttributesDescriptor("Markup//Attribute", AntColors.MARKUP_ATTRIBUTE),
            AttributesDescriptor("Markup//Attribute value", AntColors.MARKUP_ATTRIBUTE_VALUE),
            AttributesDescriptor("Markup//Text", AntColors.MARKUP_TEXT),
            AttributesDescriptor("Markup//Character reference", AntColors.MARKUP_ENTITY),
            AttributesDescriptor("Markup//Interpolation braces", AntColors.MARKUP_BRACES),
            AttributesDescriptor("Semantic//Namespace", AntColors.NAMESPACE),
            AttributesDescriptor("Semantic//Type", AntColors.TYPE),
            AttributesDescriptor("Semantic//Type parameter", AntColors.TYPE_PARAMETER),
            AttributesDescriptor("Semantic//Function declaration", AntColors.FUNCTION_DECLARATION),
            AttributesDescriptor("Semantic//Function call", AntColors.FUNCTION_CALL),
            AttributesDescriptor("Semantic//Parameter", AntColors.PARAMETER),
            AttributesDescriptor("Semantic//Variable", AntColors.VARIABLE),
            AttributesDescriptor("Semantic//Top-level variable", AntColors.GLOBAL_VARIABLE),
            AttributesDescriptor("Semantic//Field", AntColors.FIELD),
            AttributesDescriptor("Semantic//Prelude name", AntColors.PRELUDE),
        )

        private val TAGS: Map<String, TextAttributesKey> = mapOf(
            "ns" to AntColors.NAMESPACE,
            "type" to AntColors.TYPE,
            "tparam" to AntColors.TYPE_PARAMETER,
            "fdecl" to AntColors.FUNCTION_DECLARATION,
            "fcall" to AntColors.FUNCTION_CALL,
            "param" to AntColors.PARAMETER,
            "var" to AntColors.VARIABLE,
            "gvar" to AntColors.GLOBAL_VARIABLE,
            "field" to AntColors.FIELD,
            "prelude" to AntColors.PRELUDE,
        )

        private val DEMO = """
            //! Shapes computes areas on the plane.

            import "./lib/geometry.ant" <ns>Geo</ns>

            // Shape is a circle or a rectangle.
            export type <type>Shape</type> = <type>Circle</type> | <type>Rect</type>
            export type <type>Circle</type> = { <field>r</field>: float64 }
            export type <type>Rect</type> = { <field>w</field>: float64, <field>h</field>: float64 }

            // area returns the area of s.
            export func <fdecl>area</fdecl>(<param>s</param>: <type>Shape</type>): float64 {
              if <param>s</param> is <type>Circle</type> {
                return <prelude>Math</prelude>.pi * <param>s</param>.<field>r</field> * <param>s</param>.<field>r</field>
              }
              return <param>s</param>.<field>w</field> * <param>s</param>.<field>h</field>
            }

            var <gvar>shapes</gvar>: Array[<type>Shape</type>] = [Circle{ r: 1.5 }, Rect{ w: 2, h: 0x10 }]
            var <gvar>total</gvar> = <gvar>shapes</gvar>
              | <prelude>Array</prelude>.<fcall>map</fcall>({ <fcall>area</fcall>(<var>x</var>) })
              | <prelude>Array</prelude>.<fcall>sum</fcall>()
            <prelude>println</prelude>(`total: ${'$'}{<gvar>total</gvar>} over ${'$'}{<gvar>shapes</gvar>.length}\n`)
            var <gvar>ok</gvar> = <prelude>Regex</prelude>.<fcall>test</fcall>(/^\d+$/i, "42\u{1F600}\q")
            let x = 1

            func <fdecl>card</fdecl>(<param>title</param>: string, <param>items</param>: Array[string]): Html {
              <article class="card" data-count={<param>items</param>.length}>
                <h2>{<param>title</param>} &amp; more</h2>
                <ul>{<prelude>Array</prelude>.<fcall>map</fcall>(<param>items</param>, { <li>{<var>x</var>}</li> })}</ul>
                <br />
              </article>
            }
        """.trimIndent()
    }
}
