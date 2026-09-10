package app.weave

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import app.weave.backbone.CommentPolicy

/**
 * How long an open-mode comment survives if the page owner never keeps it. Matches the
 * default service-request TTL the daemon uses, so the app's idea of expiry does not outlive
 * the request that carried the comment.
 */
const val OPEN_COMMENT_TTL_MS = 15L * 60L * 1000L

/**
 * Short label describing where a comment is in its journey, or null when it is simply
 * published and needs no explanation.
 */
fun commentStatusLabel(comment: Comment): String? = when (comment.state) {
    CommentState.Sending -> "Sending"
    CommentState.Failed -> "Not sent"
    CommentState.Provisional -> "Sent, awaiting confirmation"
    CommentState.Held -> "Waiting for approval"
    CommentState.Accepted, CommentState.Dropped -> null
}

/** Image hashes referenced by a single page. */
fun Page.imageHashesOnPage(): Set<String> = buildSet {
    fun walk(e: Element) {
        if (e.type == ElementType.Media && e.mediaKind == MediaKind.Image && e.mediaContentHash.isNotBlank()) {
            add(e.mediaContentHash)
        }
        e.children.forEach(::walk)
    }
    walk(root)
}

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
    /** Whose page this is. A "your mark" stamp resolves against it at draw time. */
    ownerKey: String = "",
    onImageLongPress: ((Element) -> Unit)? = null,
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
    val hitTest = if (onImageLongPress == null) Modifier else Modifier.pointerInput(page.id) {
        detectTapGestures(onLongPress = { point ->
            page.imageElementAt(point.x, point.y, size.width.toFloat(), size.height.toFloat())
                ?.let(onImageLongPress)
        })
    }
    Canvas(modifier.then(hitTest)) {
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
                imageLookup = imageLookup,
                ownerKey = ownerKey
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
    loader: MediaLoader,
    commentStore: CommentStore,
    openComments: Boolean,
    commentPolicy: CommentPolicy,
    commentRevision: Long,
    onPostComment: (pageKey: String, body: String, openMode: Boolean, replyTo: String?) -> Unit,
    onRetryComment: (commentId: String) -> Unit,
    onSyncComments: (pageKey: String) -> Unit,
    onSaveImageToGallery: (Element) -> Unit,
    onCopyImageLocation: (Element) -> Unit,
    onSaveImageToEditor: (Element) -> Unit,
    onSaveProfileCopy: (RemoteProfile) -> Unit,
    onCopyProfileKey: (RemoteProfile) -> Unit,
    profileGroups: List<GroupDirectoryEntry> = emptyList(),
    onOpenGroup: (String) -> Unit = {},
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
    val zoom = rememberPageZoomState(profile.mainDht to pageIndex)
    var imageMenuFor by remember(profile.mainDht) { mutableStateOf<Element?>(null) }
    var showProfileMenu by remember(profile.mainDht) { mutableStateOf(false) }

    LaunchedEffect(profile.mainDht, pageIndex) { scroll.scrollTo(0) }

    // Narrow the fetch set to the page on screen. Anything queued for a page the person has
    // already swiped past is dropped before it reaches the daemon.
    LaunchedEffect(profile.mainDht, pageIndex) {
        loader.setWanted(page.imageHashesOnPage())
        val pageKey = pageKeyOf(profile.mainDht, page.id)
        while (true) {
            onSyncComments(pageKey)
            delay(60_000L)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ViewerHeader(profile, isFollowing, onToggleFollow, onBack, onNameClick = { showProfileMenu = true })
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                // Horizontal drag moves between profiles. Vertical belongs to the scroll,
                // and the page rail owns page turns.
                .pointerInput(profile.mainDht, zoom.zoomed) {
                    if (zoom.zoomed) return@pointerInput
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
                    PageCanvas(
                        page,
                        Modifier
                            .fillMaxWidth()
                            .height(canvasHeight)
                            .pinchZoom(zoom)
                            .graphicsLayer(
                                scaleX = zoom.scale,
                                scaleY = zoom.scale,
                                translationX = zoom.offset.x,
                                translationY = zoom.offset.y,
                            ),
                        imageLookup = loader::lookup,
                        ownerKey = profile.mainDht,
                        onImageLongPress = { element -> imageMenuFor = element },
                    )
                }
                if (zoom.zoomed) {
                    TextButton(onClick = { zoom.reset() }, modifier = Modifier.padding(start = 8.dp)) {
                        Text(tr("Reset zoom"), style = MaterialTheme.typography.labelSmall)
                    }
                }
                PageRail(
                    pageNames = profile.document.pages.map { it.name },
                    index = pageIndex,
                    onSelect = { pageIndex = it },
                    onPrev = { if (pageIndex > 0) pageIndex-- },
                    onNext = { if (pageIndex < profile.pageCount - 1) pageIndex++ },
                )
                if (profileGroups.isNotEmpty()) {
                    ProfileGroupsSection(profileGroups, onOpenGroup)
                }
                CommentsSection(
                    pageKey = pageKeyOf(profile.mainDht, page.id),
                    pageOwnerKey = profile.mainDht,
                    store = commentStore,
                    policy = commentPolicy,
                    externalRevision = commentRevision,
                    onPost = onPostComment,
                    onRetry = onRetryComment,
                    ownKey = ownKey,
                    ownName = ownName,
                    openMode = openComments,
                    onPosted = { scope.launch { scroll.animateScrollTo(scroll.maxValue) } },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    imageMenuFor?.let { element ->
        ImageActionDialog(
            element = element,
            onDismiss = { imageMenuFor = null },
            onSaveToGallery = { onSaveImageToGallery(element) },
            onCopyLocation = { onCopyImageLocation(element) },
            onSaveToEditor = { onSaveImageToEditor(element) },
        )
    }

    if (showProfileMenu) {
        ProfileActionDialog(
            profile = profile,
            onDismiss = { showProfileMenu = false },
            onSaveCopy = { onSaveProfileCopy(profile) },
            onCopyKey = { onCopyProfileKey(profile) },
        )
    }
}


@Composable
private fun ProfileGroupsSection(
    groups: List<GroupDirectoryEntry>,
    onOpenGroup: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("Groups", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        groups.take(12).forEach { group ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clickable { onOpenGroup(group.groupId) },
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(group.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (group.kind == GroupBranchKind.Original) "Created" else "Claimed moderation",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("→")
                }
            }
        }
    }
}

/**
 * Long-press menu for an image. The description the author wrote is shown underneath and
 * scrolls, since it can be a paragraph and truncating it would lose the only alt text there is.
 */
@Composable
private fun ImageActionDialog(
    element: Element,
    onDismiss: () -> Unit,
    onSaveToGallery: () -> Unit,
    onCopyLocation: () -> Unit,
    onSaveToEditor: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(element.mediaTitle.ifBlank { tr("Image") }) },
        text = {
            Column {
                DialogAction(tr("Save image")) { onSaveToGallery(); onDismiss() }
                DialogAction(tr("Copy image location")) { onCopyLocation(); onDismiss() }
                DialogAction(tr("Save to editor")) { onSaveToEditor(); onDismiss() }
                if (element.mediaDescription.isNotBlank()) {
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Text(tr("Description"), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    Box(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                        SelectionContainer {
                            Text(element.mediaDescription, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close")) } },
    )
}

@Composable
private fun ProfileActionDialog(
    profile: RemoteProfile,
    onDismiss: () -> Unit,
    onSaveCopy: () -> Unit,
    onCopyKey: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(profile.displayName) },
        text = {
            Column {
                DialogAction(tr("Save a copy of this profile")) { onSaveCopy(); onDismiss() }
                DialogAction(tr("Copy profile key")) { onCopyKey(); onDismiss() }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text("${tr("Pages")}: ${profile.pageCount}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "${tr("Fetched")} ${(System.currentTimeMillis() - profile.fetchedAt) / 1000} ${tr("seconds ago")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(
                        profile.mainDht,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close")) } },
    )
}

@Composable
private fun DialogAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
    )
}

@Composable
private fun ViewerHeader(
    profile: RemoteProfile,
    isFollowing: Boolean,
    onToggleFollow: () -> Unit,
    onBack: () -> Unit,
    onNameClick: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircleIconButton("\u25C0", tr("Back"), onBack)
            Spacer(Modifier.width(8.dp))
            Identicon(profile.mainDht, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).clickable(onClick = onNameClick)) {
                SelectionContainer {
                    Text(profile.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Text(
                    "${profile.pageCount} ${if (profile.pageCount == 1) tr("page") else tr("pages")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onToggleFollow) {
                Text(if (isFollowing) tr("Following") else tr("Follow"), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun CommentsSection(
    pageKey: String,
    pageOwnerKey: String,
    store: CommentStore,
    ownKey: String,
    ownName: String,
    openMode: Boolean,
    policy: CommentPolicy,
    /** Bumped by the controller whenever the local store changes, including from the network. */
    externalRevision: Long,
    onPost: (pageKey: String, body: String, openMode: Boolean, replyTo: String?) -> Unit,
    onRetry: (commentId: String) -> Unit,
    onPosted: () -> Unit,
) {
    var revision by remember(pageKey) { mutableIntStateOf(0) }
    var draft by remember(pageKey) { mutableStateOf("") }
    var replyDraft by remember(pageKey) { mutableStateOf("") }
    var replyingTo by remember(pageKey) { mutableStateOf<String?>(null) }
    val comments = remember(pageKey, revision, externalRevision) { store.forPage(pageKey) }
    // Your own comments that this page's owner hasn't released yet. Shown to you only so
    // the post button doesn't look broken — the decision is not yours to make.
    val hidden = remember(pageKey, revision, externalRevision) { store.hiddenForPage(pageKey) }
    var showHidden by remember(pageKey) { mutableStateOf(false) }
    val myPending = remember(pageKey, revision, externalRevision) {
        store.awaitingApproval(ownKey).filter { it.pageKey == pageKey }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(
            if (comments.isEmpty()) tr("Comments") else "${tr("Comments")} (${comments.size})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            when (policy) {
                CommentPolicy.Closed -> tr("This profile has comments turned off.")
                CommentPolicy.Moderated -> tr("Comments attach to this page after the owner approves them.")
                CommentPolicy.Open -> tr("Comments are public and attach to this page.")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
        )

        if (comments.isEmpty()) {
            Text(
                tr("Nothing here yet."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        val roots = remember(comments) { buildCommentTree(comments) }
        val threadView = rememberThreadViewState(pageKey)

        if (roots.isNotEmpty()) {
            ThreadedComments(
                roots = roots,
                view = threadView,
                replyingTo = replyingTo,
                replyDraft = replyDraft,
                canReply = policy != CommentPolicy.Closed,
                onReplyDraftChange = { replyDraft = it },
                onStartReply = { id -> replyingTo = id; replyDraft = "" },
                onSubmitReply = { parentId ->
                    onPost(pageKey, replyDraft, openMode, parentId)
                    replyDraft = ""
                    replyingTo = null
                    revision++
                    onPosted()
                },
                onHide = { id -> store.hideForMe(id); revision++ },
                onRetry = { id -> onRetry(id); revision++ },
            )
        }

        if (hidden.isNotEmpty()) {
            TextButton(onClick = { showHidden = !showHidden }, contentPadding = PaddingValues(0.dp)) {
                Text(
                    if (showHidden) "${tr("Hide")} ${hidden.size}" else "${tr("Show more")} ${hidden.size}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (showHidden) {
            hidden.forEach { comment ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Identicon(comment.authorKey.ifBlank { comment.authorName }, size = 24.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            comment.authorName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            displayBody(comment, expanded = false),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { store.unhide(comment.id); revision++ },
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) { Text(tr("Unhide"), style = MaterialTheme.typography.labelSmall) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }

        myPending.forEach { pending ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (pending.state == CommentState.Provisional) tr("Visible to everyone, but the owner hasn't kept it yet")
                        else tr("Waiting for this page's owner to release it"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(pending.body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        if (policy == CommentPolicy.Closed) return@Column

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(MAX_COMMENT_CHARS) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text(if (roots.isEmpty()) tr("Leave a comment") else tr("Start a new thread")) },
            minLines = 2,
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = {
                    // Posting goes through the controller: it writes to this device's own
                    // comment record, notifies the page owner, and updates the local store.
                    onPost(pageKey, draft, openMode, null)
                    draft = ""
                    revision++
                    onPosted()
                },
                enabled = draft.isNotBlank()
            ) { Text(tr("Post")) }
        }
    }
}
