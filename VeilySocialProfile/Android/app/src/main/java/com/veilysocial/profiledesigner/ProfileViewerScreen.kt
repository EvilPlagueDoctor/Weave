package com.veilysocial.profiledesigner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * How long an open-mode comment survives if the page owner never keeps it. Matches the
 * default service-request TTL the daemon uses, so the app's idea of expiry does not outlive
 * the request that carried the comment.
 */
const val OPEN_COMMENT_TTL_MS = 15L * 60L * 1000L

/**
 * Fixed-width, vertically scrolling page render.
 *
 * The page keeps its authored aspect ratio, but width is pinned to the viewport instead of
 * the whole page being scaled to fit. That means the author knows the horizontal scale when
 * they design, text stays legible on a phone, and long pages simply scroll.
 */
@Composable
fun PageCanvas(
    page: Page,
    modifier: Modifier = Modifier,
    widgetLookup: (Element) -> WidgetProgram? = { null },
    imageLookup: (Element) -> ImageBitmap? = { null },
) {
    val strings = RenderStrings(
        mediaKinds = listOf(
            stringResource(R.string.media_image),
            stringResource(R.string.media_audio),
            stringResource(R.string.media_video)
        ),
        mediaSuffix = stringResource(R.string.media_preview_suffix),
        widgetSuffix = stringResource(R.string.widget_preview_suffix),
        widgetPaused = stringResource(R.string.widget_paused_short)
    )
    Canvas(modifier) {
        val root = page.root
        val rootRect = Rect(0f, 0f, size.width, size.height)
        drawBackground(rootRect, root.background)
        root.children.sortedBy { it.rect.zIndex }.forEach {
            drawElement(
                it, rootRect, "",
                selectChildren = false,
                strings = strings,
                inlineEditingId = null,
                widgetLookup = widgetLookup,
                imageLookup = imageLookup
            )
        }
    }
}

/** Height a page occupies at a given viewport width. aspectRatio is width/height. */
private fun pageHeightFor(page: Page, width: Dp): Dp = width / page.aspectRatio.coerceIn(MIN_PAGE_ASPECT, MAX_PAGE_ASPECT)

@Composable
fun ProfileViewerScreen(
    profile: RemoteProfile,
    ownKey: String,
    ownName: String,
    commentStore: CommentStore,
    openComments: Boolean,
    isFollowing: Boolean,
    onToggleFollow: () -> Unit,
    onPrevProfile: (() -> Unit)?,
    onNextProfile: (() -> Unit)?,
    onBack: () -> Unit,
) {
    var pageIndex by remember(profile.mainDht) { mutableIntStateOf(0) }
    val page = profile.page(pageIndex) ?: return
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(profile.mainDht, pageIndex) { scroll.scrollTo(0) }

    Column(Modifier.fillMaxSize()) {
        ViewerHeader(profile, isFollowing, onToggleFollow, onBack)
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                // Horizontal drag moves between profiles. Vertical belongs to the scroll,
                // and the page rail owns page turns.
                .pointerInput(profile.mainDht) {
                    var travelled = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { travelled = 0f },
                        onDragEnd = {
                            val threshold = 90f * density
                            when {
                                travelled <= -threshold -> onNextProfile?.invoke()
                                travelled >= threshold -> onPrevProfile?.invoke()
                            }
                        }
                    ) { _, dragAmount -> travelled += dragAmount }
                }
        ) {
            val canvasWidth = maxWidth
            val canvasHeight = pageHeightFor(page, canvasWidth)
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Surface(color = Color.White, tonalElevation = 0.dp) {
                    PageCanvas(page, Modifier.fillMaxWidth().height(canvasHeight))
                }
                PageRail(
                    pageNames = profile.document.pages.map { it.name },
                    index = pageIndex,
                    onSelect = { pageIndex = it },
                    onPrev = { if (pageIndex > 0) pageIndex-- },
                    onNext = { if (pageIndex < profile.pageCount - 1) pageIndex++ },
                )
                CommentsSection(
                    pageKey = pageKeyOf(profile.mainDht, page.id),
                    pageOwnerKey = profile.mainDht,
                    store = commentStore,
                    ownKey = ownKey,
                    ownName = ownName,
                    openMode = openComments,
                    onPosted = { scope.launch { scroll.animateScrollTo(scroll.maxValue) } },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ViewerHeader(profile: RemoteProfile, isFollowing: Boolean, onToggleFollow: () -> Unit, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("\u2190") }
            Identicon(profile.mainDht, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                SelectionContainer {
                    Text(profile.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Text(
                    "${profile.pageCount} page${if (profile.pageCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onToggleFollow) {
                Text(if (isFollowing) "Following" else "Follow", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * Page controls belong to the page, not to the app chrome. This scrolls away with the
 * content and does not exist on screens where there is no page to turn.
 */
@Composable
fun PageRail(
    pageNames: List<String>,
    index: Int,
    onSelect: (Int) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    if (pageNames.size <= 1) return
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onPrev, enabled = index > 0, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("\u2190") }
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                pageNames.forEachIndexed { i, name ->
                    val selected = i == index
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.padding(horizontal = 3.dp)
                    ) {
                        Box(
                            Modifier
                                .size(if (selected) 22.dp else 10.dp, 10.dp)
                                .clickable { onSelect(i) }
                        )
                    }
                }
            }
            TextButton(onClick = onNext, enabled = index < pageNames.lastIndex, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("\u2192") }
        }
    }
    Text(
        pageNames[index],
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
fun CommentsSection(
    pageKey: String,
    pageOwnerKey: String,
    store: CommentStore,
    ownKey: String,
    ownName: String,
    openMode: Boolean,
    onPosted: () -> Unit,
) {
    var revision by remember(pageKey) { mutableIntStateOf(0) }
    var draft by remember(pageKey) { mutableStateOf("") }
    val comments = remember(pageKey, revision) { store.forPage(pageKey) }
    // Your own comments that this page's owner hasn't released yet. Shown to you only so
    // the post button doesn't look broken — the decision is not yours to make.
    val myPending = remember(pageKey, revision) {
        store.awaitingApproval(ownKey).filter { it.pageKey == pageKey }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(
            if (comments.isEmpty()) "Comments" else "Comments (${comments.size})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            if (openMode) "Comments are public, attach to this page, and stay until the owner keeps them."
            else "Comments are public and attach to this page.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
        )

        if (comments.isEmpty()) {
            Text(
                "Nothing here yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        comments.forEach { comment ->
            var expanded by remember(comment.id) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Identicon(comment.authorKey.ifBlank { comment.authorName }, size = 28.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(comment.authorName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        if (comment.state == CommentState.Provisional) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(999.dp)
                            ) {
                                Text(
                                    "Not kept yet",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    SelectionContainer {
                        Text(displayBody(comment, expanded), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (comment.body.length > COMMENT_TRUNCATE_CHARS) {
                        TextButton(
                            onClick = { expanded = !expanded },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(if (expanded) "Show less" else "Show more", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                TextButton(onClick = { store.hideForMe(comment.id); revision++ }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("Hide", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        myPending.forEach { pending ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (pending.state == CommentState.Provisional) "Visible to everyone, but the owner hasn't kept it yet"
                        else "Waiting for this page's owner to release it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(pending.body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(MAX_COMMENT_CHARS) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text("Leave a comment") },
            minLines = 2,
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = {
                    val state = triageComment(draft, ownKey, pageOwnerKey, store.forPage(pageKey), openMode)
                    val expiresAt =
                        if (openMode && state == CommentState.Provisional) System.currentTimeMillis() + OPEN_COMMENT_TTL_MS
                        else 0L
                    store.add(
                        pageKey, ownKey, ownName, draft, state,
                        if (openMode) CommentOrigin.Open else CommentOrigin.Direct,
                        expiresAt,
                    )
                    draft = ""
                    revision++
                    onPosted()
                },
                enabled = draft.isNotBlank()
            ) { Text("Post") }
        }
    }
}
