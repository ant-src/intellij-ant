package com.github.antsrc.ant.editing

import com.github.antsrc.ant.lexer.AntTokenTypes as T
import com.intellij.formatting.Alignment
import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.Wrap
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.tree.IElementType

/**
 * The formatting model that indents a line on Enter and when a closing bracket is typed: the
 * contents of `{ }`, `( )`, `[ ]`, `${ }`, a `{ }` in markup, and an element are one level deeper
 * than the line that opens them (fmt.md section 4). Reformat Code is `ant fmt`, through the language
 * server, which overrides this model; it leaves spacing alone and only sets indentation.
 */
class AntFormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(context: FormattingContext): FormattingModel {
        val file = context.containingFile
        val leaves = ArrayList<ASTNode>()
        var node = file.node.firstChildNode
        while (node != null) {
            if (node.elementType != TokenType.WHITE_SPACE) leaves.add(node)
            node = node.treeNext
        }
        val root = GroupBlock(file.textRange, build(leaves, 0, leaves.size, null), Indent.getNoneIndent(), inner = false)
        return FormattingModelProvider.createFormattingModelForPsiFile(file, root, context.codeStyleSettings)
    }

    /** Groups leaves[from, to) into blocks, one per bracket pair or element, and returns them. */
    private fun build(leaves: List<ASTNode>, from: Int, to: Int, closers: Set<IElementType>?): List<Block> {
        val blocks = ArrayList<Block>()
        var i = from
        while (i < to) {
            val leaf = leaves[i]
            val type = leaf.elementType
            val close = CLOSE_OF[type]
            if (type == T.TAG_OPEN) {
                val end = elementEnd(leaves, i, to)
                blocks.add(element(leaves, i, end))
                i = end
            } else if (close != null) {
                val end = matchingClose(leaves, i + 1, to, close)
                blocks.add(group(leaves, i, end, close))
                i = if (end < to) end + 1 else end
            } else {
                blocks.add(LeafBlock(leaf, Indent.getNoneIndent()))
                i++
            }
        }
        return blocks
    }

    /**
     * A bracket pair: the opener at open, the indented contents, and the closer at end when
     * end is inside the leaves being grouped (otherwise the pair is unclosed and ends at end - 1).
     */
    private fun group(leaves: List<ASTNode>, open: Int, end: Int, close: Set<IElementType>): Block {
        val closed = end < leaves.size && leaves[end].elementType in close
        val last = if (closed) end else (end - 1).coerceAtLeast(open)
        val range = TextRange(leaves[open].startOffset, leaves[last].textRange.endOffset)
        val children = ArrayList<Block>()
        children.add(LeafBlock(leaves[open], Indent.getNoneIndent()))
        for (b in build(leaves, open + 1, end, close)) children.add(Indented(b))
        if (closed) children.add(LeafBlock(leaves[end], Indent.getNoneIndent()))
        return GroupBlock(range, children, Indent.getNoneIndent(), inner = true)
    }

    /** The index of the closer of the bracket opened before from, or to when there is none. */
    private fun matchingClose(leaves: List<ASTNode>, from: Int, to: Int, close: Set<IElementType>): Int {
        var depth = 0
        var i = from
        while (i < to) {
            val type = leaves[i].elementType
            if (depth == 0 && type in close) return i
            if (type in T.OPENERS && type != T.TAG_OPEN) depth++
            if (type in T.CLOSERS && type != T.END_TAG_OPEN && depth > 0) depth--
            i++
        }
        return to
    }

    /** An element, leaves[open, end): its start tag, its indented content, and its end tag. */
    private fun element(leaves: List<ASTNode>, open: Int, end: Int): Block {
        val startTagEnd = indexOf(leaves, open + 1, end) { it == T.TAG_CLOSE || it == T.TAG_SELF_CLOSE }
        val children = ArrayList<Block>()
        children.add(LeafBlock(leaves[open], Indent.getNoneIndent()))
        for (b in build(leaves, open + 1, startTagEnd, null)) children.add(Indented(b))
        if (startTagEnd < end) children.add(LeafBlock(leaves[startTagEnd], Indent.getNoneIndent()))
        if (startTagEnd < end && leaves[startTagEnd].elementType == T.TAG_CLOSE) {
            val endTag = endTagStart(leaves, startTagEnd + 1, end)
            for (b in build(leaves, startTagEnd + 1, endTag, null)) children.add(Indented(b))
            for (k in endTag until end) children.add(LeafBlock(leaves[k], Indent.getNoneIndent()))
        }
        val range = TextRange(leaves[open].startOffset, leaves[end - 1].textRange.endOffset)
        return GroupBlock(range, children, Indent.getNoneIndent(), inner = true)
    }

    /** The index after the element that opens at open: after its `/>` or its end tag's `>`. */
    private fun elementEnd(leaves: List<ASTNode>, open: Int, to: Int): Int {
        val startTagEnd = indexOf(leaves, open + 1, to) { it == T.TAG_CLOSE || it == T.TAG_SELF_CLOSE }
        if (startTagEnd >= to) return to
        if (leaves[startTagEnd].elementType == T.TAG_SELF_CLOSE) return startTagEnd + 1
        val endTag = endTagStart(leaves, startTagEnd + 1, to)
        if (endTag >= to) return to
        val close = indexOf(leaves, endTag + 1, to) { it == T.TAG_CLOSE }
        return (close + 1).coerceAtMost(to)
    }

    /** The index of the `</` that closes the content starting at from, skipping nested elements. */
    private fun endTagStart(leaves: List<ASTNode>, from: Int, to: Int): Int {
        var i = from
        while (i < to) {
            when (leaves[i].elementType) {
                T.END_TAG_OPEN -> return i
                T.TAG_OPEN -> i = elementEnd(leaves, i, to)
                T.MARKUP_EXPR_START -> i = matchingClose(leaves, i + 1, to, setOf(T.MARKUP_EXPR_END)) + 1
                else -> i++
            }
        }
        return to
    }

    private inline fun indexOf(leaves: List<ASTNode>, from: Int, to: Int, test: (IElementType) -> Boolean): Int {
        var i = from
        while (i < to && !test(leaves[i].elementType)) i++
        return i
    }

    companion object {
        private val CLOSE_OF: Map<IElementType, Set<IElementType>> = mapOf(
            T.LBRACE to setOf(T.RBRACE),
            T.LPAREN to setOf(T.RPAREN),
            T.LBRACKET to setOf(T.RBRACKET),
            T.INTERPOLATION_START to setOf(T.INTERPOLATION_END),
            T.MARKUP_EXPR_START to setOf(T.MARKUP_EXPR_END),
        )
    }
}

private class LeafBlock(val node: ASTNode, private val indent: Indent) : Block {
    override fun getTextRange(): TextRange = node.textRange
    override fun getSubBlocks(): List<Block> = emptyList()
    override fun getWrap(): Wrap? = null
    override fun getIndent() = indent
    override fun getAlignment(): Alignment? = null
    override fun getSpacing(child1: Block?, child2: Block): Spacing? = null
    override fun getChildAttributes(newChildIndex: Int) = ChildAttributes(Indent.getNoneIndent(), null)
    override fun isIncomplete() = false
    override fun isLeaf() = true
}

private class GroupBlock(private val range: TextRange, private val children: List<Block>, private val indent: Indent, private val inner: Boolean) : Block {
    /** Whether the last child closes the group: a closing bracket, or the > or /> of a tag. */
    private val closed = (children.lastOrNull() as? LeafBlock)?.node?.elementType.let {
        it != null && (it in T.CLOSERS || it == T.TAG_CLOSE || it == T.TAG_SELF_CLOSE)
    }

    override fun getTextRange() = range
    override fun getSubBlocks() = children
    override fun getWrap(): Wrap? = null
    override fun getIndent() = indent
    override fun getAlignment(): Alignment? = null
    override fun getSpacing(child1: Block?, child2: Block): Spacing? = null

    /** A new line inside the brackets is indented; one after the closer, or at the top level, is not. */
    override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
        if (!inner) return ChildAttributes(Indent.getNoneIndent(), null)
        return if (closed && newChildIndex >= children.size) ChildAttributes(Indent.getNoneIndent(), null) else ChildAttributes(Indent.getNormalIndent(), null)
    }

    override fun isIncomplete() = inner && !closed

    override fun isLeaf() = children.isEmpty()
}

/** A child block one level deeper than the block that holds it. */
private class Indented(private val block: Block) : Block by block {
    override fun getIndent(): Indent = Indent.getNormalIndent()
}
