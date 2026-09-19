package app.weave

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
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


data class PageImagePlacement(
    val element: Element,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

fun Page.imagePlacementsOnPage(): List<PageImagePlacement> = buildList {
    fun walk(e: Element, x: Float, y: Float, width: Float, height: Float) {
        if (!e.rect.visible) return
        val childX = x + e.rect.x * width
        val childY = y + e.rect.y * height
        val childWidth = e.rect.width * width
        val childHeight = e.rect.height * height
        if (e.type == ElementType.Media && e.mediaKind == MediaKind.Image) {
            add(PageImagePlacement(e, childX, childY, childWidth, childHeight))
        }
        e.children.forEach { walk(it, childX, childY, childWidth, childHeight) }
    }
    root.children.forEach { walk(it, 0f, 0f, 1f, 1f) }
}

fun Page.audioElementsOnPage(): List<Element> = buildList {
    fun walk(e: Element) {
        if (e.type == ElementType.Media &&
            e.mediaKind == MediaKind.Audio &&
            e.mediaContentHash.isNotBlank()
        ) add(e)
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
    onWidgetTap: ((Element) -> Unit)? = null,
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
    val hitTest = if (onImageLongPress == null && onWidgetTap == null) Modifier else Modifier.pointerInput(page.id, onImageLongPress, onWidgetTap) {
        detectTapGestures(
            onTap = { point ->
                onWidgetTap?.let { callback ->
                    page.widgetElementAt(point.x, point.y, size.width.toFloat(), size.height.toFloat())
                        ?.let(callback)
                }
            },
            onLongPress = { point ->
                onImageLongPress?.let { callback ->
                    page.imageElementAt(point.x, point.y, size.width.toFloat(), size.height.toFloat())
                        ?.let(callback)
                }
            }
        )
    }
    val imagePlacements = remember(page) { page.imagePlacementsOnPage() }
    val visibleImagePlacements = remember(page, imagePlacements) {
        imagePlacements.filter { it.element.rect.visible && it.width > 0f && it.height > 0f }
    }
    val renderedImageIds = visibleImagePlacements
        .mapNotNull { placement -> imageLookup(placement.element)?.let { placement.element.id } }
        .toSet()

    BoxWithConstraints(modifier.then(hitTest)) {
        Canvas(Modifier.matchParentSize()) {
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
                    ownerKey = ownerKey,
                    skipImageIds = renderedImageIds,
                )
            }
        }

        visibleImagePlacements.forEach { placement ->
            val bitmap = imageLookup(placement.element)
            if (bitmap != null) {
                Box(
                    Modifier
                        .offset(x = maxWidth * placement.x, y = maxHeight * placement.y)
                        .size(width = maxWidth * placement.width, height = maxHeight * placement.height)
                ) {
                    FilteredImage(
                        contentId = "profile-image:${placement.element.mediaContentHash.ifBlank { placement.element.id }}",
                        bitmap = bitmap,
                        modifier = Modifier.fillMaxSize(),
                    ) { gateModifier ->
                        Image(
                            bitmap = bitmap,
                            contentDescription = placement.element.mediaTitle.ifBlank { "Profile image" },
                            modifier = gateModifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
        }
    }
}

/** Height a page occupies at a given viewport width. aspectRatio is width/height. */
private fun pageHeightFor(page: Page, width: Dp): Dp = width / page.aspectRatio.coerceIn(MIN_PAGE_ASPECT, MAX_PAGE_ASPECT)

@Composable
private fun ActivatedWidgetLayer(
    page: Page,
    programs: Map<String, WidgetProgram>,
    networkHosts: Map<String, WidgetNetworkHost>,
    loading: Map<String, Boolean>,
    errors: Map<String, String>,
    onRetry: (Element) -> Unit,
    onClose: (Element) -> Unit,
    modifier: Modifier = Modifier,
) {
    val placements = remember(page) { page.widgetPlacements() }
    BoxWithConstraints(modifier) {
        placements.forEach { placement ->
            val element = placement.element
            val nodeModifier = Modifier
                .offset(x = maxWidth * placement.x, y = maxHeight * placement.y)
                .size(width = maxWidth * placement.width, height = maxHeight * placement.height)
            val program = programs[element.id]
            when {
                program != null -> Box(nodeModifier) {
                    WidgetRuntimeView(program = program, modifier = Modifier.fillMaxSize(), interactive = true, networkHost = networkHosts[element.id])
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = .92f),
                        shape = RoundedCornerShape(50),
                        tonalElevation = 3.dp,
                        shadowElevation = 2.dp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(30.dp).clickable { onClose(element) },
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("×", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                loading[element.id] == true -> Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = nodeModifier,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.widget_loading), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                errors[element.id] != null -> Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .96f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = nodeModifier.clickable { onRetry(element) },
                ) {
                    Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.widget_load_failed), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.widget_retry), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileViewerScreen(
    profile: RemoteProfile,
    ownKey: String,
    ownName: String,
    loader: MediaLoader,
    media: LocalMediaStore,
    controller: SocialNetworkController,
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
    onOpenLink: (DetectedLink) -> Unit = {},
    isFollowing: Boolean,
    onToggleFollow: () -> Unit,
    onPrevProfile: (() -> Unit)?,
    onNextProfile: (() -> Unit)?,
    onBack: () -> Unit,
    widgetLoader: RemoteWidgetLoader,
) {
    var pageIndex by remember(profile.mainDht) { mutableIntStateOf(0) }
    val page = profile.page(pageIndex) ?: return
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val zoom = rememberPageZoomState(profile.mainDht to pageIndex)
    var imageMenuFor by remember(profile.mainDht) { mutableStateOf<Element?>(null) }
    var showProfileMenu by remember(profile.mainDht) { mutableStateOf(false) }
    val activeWidgets = remember(profile.mainDht) { mutableStateMapOf<String, WidgetProgram>() }
    val widgetNetworkHosts = remember(profile.mainDht) { mutableStateMapOf<String, WidgetNetworkHost>() }
    val loadingWidgets = remember(profile.mainDht) { mutableStateMapOf<String, Boolean>() }
    val widgetErrors = remember(profile.mainDht) { mutableStateMapOf<String, String>() }

    fun activateWidget(element: Element) {
        if (activeWidgets.containsKey(element.id) || loadingWidgets[element.id] == true) return
        loadingWidgets[element.id] = true
        widgetErrors.remove(element.id)
        scope.launch {
            widgetLoader.load(element)
                .onSuccess { program ->
                    activeWidgets[element.id] = program
                    controller.widgetNetworkHost(profile.mainDht, element, program)?.let { widgetNetworkHosts[element.id] = it }
                }
                .onFailure { error -> widgetErrors[element.id] = error.message ?: "Widget could not be loaded" }
            loadingWidgets.remove(element.id)
        }
    }

    fun closeWidget(element: Element) {
        widgetNetworkHosts.remove(element.id)?.close()
        activeWidgets.remove(element.id)
        loadingWidgets.remove(element.id)
        widgetErrors.remove(element.id)
    }

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
                                else -> Unit
                            }
                        }
                    ) { _, dragAmount -> travelled += dragAmount }
                }
        ) {
            val canvasWidth = maxWidth
            val canvasHeight = pageHeightFor(page, canvasWidth)
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Surface(color = Color.White, tonalElevation = 0.dp) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(canvasHeight)
                            .pinchZoom(zoom)
                            .graphicsLayer(
                                scaleX = zoom.scale,
                                scaleY = zoom.scale,
                                translationX = zoom.offset.x,
                                translationY = zoom.offset.y,
                            )
                    ) {
                        PageCanvas(
                            page,
                            Modifier.matchParentSize(),
                            imageLookup = loader::lookup,
                            ownerKey = profile.mainDht,
                            onImageLongPress = { element -> imageMenuFor = element },
                            onWidgetTap = ::activateWidget,
                        )
                        ActivatedWidgetLayer(
                            page = page,
                            programs = activeWidgets,
                            networkHosts = widgetNetworkHosts,
                            loading = loadingWidgets,
                            errors = widgetErrors,
                            onRetry = ::activateWidget,
                            onClose = ::closeWidget,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
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
                val pageAudio = page.audioElementsOnPage()
                if (pageAudio.isNotEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pageAudio.forEach { element ->
                            WeaveAudioPlayer(
                                title = element.mediaTitle.ifBlank { "Audio" },
                                contentHash = element.mediaContentHash,
                                recordKey = element.mediaRecordKey,
                                media = media,
                                controller = controller,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
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
                    onOpenLink = onOpenLink,
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
                DialogAction("Copy image link") { onCopyLocation(); onDismiss() }
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
    onOpenLink: (DetectedLink) -> Unit = {},
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
                else -> tr("Comments")
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
                onLink = onOpenLink,
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
                        AutoLinkText(
                            text = displayBody(comment, expanded = false),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            onLink = onOpenLink,
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
