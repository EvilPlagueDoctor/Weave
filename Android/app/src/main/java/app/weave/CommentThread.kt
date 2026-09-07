package app.weave

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// Tree
// ---------------------------------------------------------------------------

class ThreadNode(val comment: Comment, val children: MutableList<ThreadNode> = mutableListOf()) {
    /** Everything below this node, used for the count on a collapsed thread. */
    val descendantCount: Int
        get() = children.sumOf { 1 + it.descendantCount }
}

/**
 * Builds reply trees from a flat comment list.
 *
 * A reply whose parent hasn't arrived yet is promoted to the top level rather than dropped.
 * Comments sync out of order — the reply can easily land before the thing it answers — and
 * hiding it until the parent shows up would look like the network ate it.
 */
fun buildCommentTree(comments: List<Comment>): List<ThreadNode> {
    val nodes = comments.associate { it.id to ThreadNode(it) }
    val roots = mutableListOf<ThreadNode>()

    comments.forEach { comment ->
        val node = nodes.getValue(comment.id)
        val parent = comment.replyTo?.let { nodes[it] }
        // A comment can never be its own ancestor, but a malformed or hostile record could
        // claim otherwise, so anything that isn't a clean parent link becomes a root.
        if (parent != null && parent !== node && !parent.isDescendantOf(node)) {
            parent.children.add(node)
        } else {
            roots.add(node)
        }
    }

    fun sort(list: MutableList<ThreadNode>) {
        list.sortBy { it.comment.createdAt }
        list.forEach { sort(it.children) }
    }
    sort(roots)
    return roots
}

private fun ThreadNode.isDescendantOf(candidate: ThreadNode): Boolean {
    if (this === candidate) return true
    return candidate.children.any { this.isDescendantOf(it) }
}

// ---------------------------------------------------------------------------
// Flattening
// ---------------------------------------------------------------------------

sealed interface ThreadItem {
    val depth: Int
    val threadIndex: Int

    data class Entry(
        val node: ThreadNode,
        override val depth: Int,
        override val threadIndex: Int,
        val collapsed: Boolean,
    ) : ThreadItem

    /** "Show N more replies" under a parent whose children are partly or fully folded. */
    data class More(
        val parentId: String,
        val count: Int,
        override val depth: Int,
        override val threadIndex: Int,
    ) : ThreadItem
}

/** Direct replies shown under a top-level comment before the rest fold away. */
private const val REPLIES_SHOWN_BY_DEFAULT = 2

@Stable
class ThreadViewState {
    // Compose has no observable set, so these are state maps used as sets. The values are
    // irrelevant; the keys are what recomposition tracks.
    private val expandedIds = mutableStateMapOf<String, Unit>()
    private val collapsedIds = mutableStateMapOf<String, Unit>()

    val expanded: Set<String> get() = expandedIds.keys
    val collapsed: Set<String> get() = collapsedIds.keys

    fun toggleExpanded(id: String) {
        if (expandedIds.remove(id) == null) expandedIds[id] = Unit
    }

    fun toggleCollapsed(id: String) {
        if (collapsedIds.remove(id) == null) collapsedIds[id] = Unit
    }
}

@Composable
fun rememberThreadViewState(key: Any?): ThreadViewState = remember(key) { ThreadViewState() }

/**
 * Turns the tree into the rows actually drawn.
 *
 * Top-level comments always show. Their direct replies show two at a time. Anything deeper
 * stays folded until asked for, which keeps a long argument from burying the next thread.
 */
fun flattenThreads(roots: List<ThreadNode>, state: ThreadViewState): List<ThreadItem> {
    val out = mutableListOf<ThreadItem>()

    fun emitChildren(parent: ThreadNode, childDepth: Int, threadIndex: Int) {
        val children = parent.children
        if (children.isEmpty()) return
        val isExpanded = parent.comment.id in state.expanded
        val limit = when {
            isExpanded -> children.size
            childDepth <= 1 -> REPLIES_SHOWN_BY_DEFAULT
            else -> 0
        }
        children.take(limit).forEach { child ->
            val childCollapsed = child.comment.id in state.collapsed
            out.add(ThreadItem.Entry(child, childDepth, threadIndex, childCollapsed))
            if (!childCollapsed) emitChildren(child, childDepth + 1, threadIndex)
        }
        val remaining = children.size - limit
        if (remaining > 0) {
            out.add(ThreadItem.More(parent.comment.id, remaining, childDepth, threadIndex))
        }
    }

    roots.forEachIndexed { index, root ->
        val rootCollapsed = root.comment.id in state.collapsed
        out.add(ThreadItem.Entry(root, 0, index, rootCollapsed))
        if (!rootCollapsed) emitChildren(root, 1, index)
    }
    return out
}

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------

/** Indent per level. Narrow on purpose: deep threads should not run out of width. */
private val DEPTH_STEP = 14.dp

@Composable
fun ThreadedComments(
    roots: List<ThreadNode>,
    view: ThreadViewState,
    replyingTo: String?,
    replyDraft: String,
    canReply: Boolean,
    onReplyDraftChange: (String) -> Unit,
    onStartReply: (String?) -> Unit,
    onSubmitReply: (parentId: String) -> Unit,
    onHide: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    val items = remember(roots, view.expanded.toList(), view.collapsed.toList()) {
        flattenThreads(roots, view)
    }

    Column(Modifier.fillMaxWidth()) {
        items.forEach { item ->
            // Alternating tint per top-level thread. This is the only cue that survives deep
            // indentation, where the rails all look alike and the left edge is far away.
            val tint =
                if (item.threadIndex % 2 == 0) Color.Transparent
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f)

            // IntrinsicSize.Min lets the depth rails stretch to whatever height the entry
            // turns out to be. Without it fillMaxHeight inside a Row resolves to zero and
            // the rails do not draw at all.
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(tint)) {
                DepthRails(item.depth)
                when (item) {
                    is ThreadItem.Entry -> CommentEntry(
                        item = item,
                        isReplyTarget = replyingTo == item.node.comment.id,
                        replyDraft = replyDraft,
                        canReply = canReply,
                        onToggleCollapsed = { view.toggleCollapsed(item.node.comment.id) },
                        onReplyDraftChange = onReplyDraftChange,
                        onStartReply = onStartReply,
                        onSubmitReply = onSubmitReply,
                        onHide = onHide,
                        onRetry = onRetry,
                    )

                    is ThreadItem.More -> Text(
                        "${tr("Show more")} ${item.count} ${if (item.count == 1) tr("reply") else tr("replies")}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { view.toggleExpanded(item.parentId) }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * One hairline per level of depth. Cheaper to read than indentation alone: the number of
 * rails tells you how deep you are without counting pixels from the edge.
 */
@Composable
private fun DepthRails(depth: Int) {
    if (depth == 0) return
    Row {
        repeat(depth) {
            Box(Modifier.width(DEPTH_STEP).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .28f))
                )
            }
        }
    }
}

@Composable
private fun CommentEntry(
    item: ThreadItem.Entry,
    isReplyTarget: Boolean,
    replyDraft: String,
    canReply: Boolean,
    onToggleCollapsed: () -> Unit,
    onReplyDraftChange: (String) -> Unit,
    onStartReply: (String?) -> Unit,
    onSubmitReply: (parentId: String) -> Unit,
    onHide: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    val comment = item.node.comment
    var bodyExpanded by remember(comment.id) { mutableStateOf(false) }
    val hiddenCount = item.node.descendantCount

    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggleCollapsed),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Identicon(comment.authorKey.ifBlank { comment.authorName }, size = if (item.depth == 0) 26.dp else 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                comment.authorName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            commentStatusLabel(comment)?.let { label ->
                Spacer(Modifier.width(6.dp))
                Surface(
                    color = if (comment.state == CommentState.Failed) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        tr(label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (item.collapsed && hiddenCount > 0) {
                Text(
                    "+$hiddenCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                if (item.collapsed) "\u25B8" else "\u25BE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (item.collapsed) return@Column

        SelectionContainer {
            Text(
                displayBody(comment, bodyExpanded),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, start = 34.dp),
            )
        }
        if (comment.body.length > COMMENT_TRUNCATE_CHARS) {
            Text(
                if (bodyExpanded) tr("Show less") else tr("Show more"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { bodyExpanded = !bodyExpanded }
                    .padding(start = 34.dp, top = 2.dp, bottom = 2.dp),
            )
        }

        Row(Modifier.padding(start = 30.dp), verticalAlignment = Alignment.CenterVertically) {
            if (canReply) {
                TextButton(
                    onClick = { onStartReply(if (isReplyTarget) null else comment.id) },
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) {
                    Text(
                        if (isReplyTarget) tr("Cancel") else tr("Reply"),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (comment.state == CommentState.Failed) {
                TextButton(
                    onClick = { onRetry(comment.id) },
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) { Text(tr("Retry"), style = MaterialTheme.typography.labelSmall) }
            }
            TextButton(
                onClick = { onHide(comment.id) },
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) { Text(tr("Hide"), style = MaterialTheme.typography.labelSmall) }
        }

        if (isReplyTarget) {
            // The composer sits under the comment being answered rather than at the foot of
            // the page, so replying deep in a thread does not mean scrolling away from it.
            OutlinedTextField(
                value = replyDraft,
                onValueChange = { onReplyDraftChange(it.take(MAX_COMMENT_CHARS)) },
                modifier = Modifier.fillMaxWidth().padding(start = 30.dp, top = 4.dp),
                label = { Text("${tr("Reply to")} ${comment.authorName}") },
                minLines = 2,
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 30.dp, top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = { onSubmitReply(comment.id) },
                    enabled = replyDraft.isNotBlank(),
                ) { Text(tr("Reply")) }
            }
        }
    }
}
