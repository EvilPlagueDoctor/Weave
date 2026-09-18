package app.weave

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.content.Intent
import android.net.Uri
import android.content.res.Configuration
import java.util.Locale

/**
 * Root of the app.
 *
 * The social surface is the shell and the editor is a destination inside it — the reverse
 * of the original arrangement, where a designer with nine tools was the first thing a new
 * user saw. First run still lands on Me, because on day one there is nothing else to look at.
 */
@Composable
fun WeaveApp() {
    val context = LocalContext.current
    val state = remember { EditorState(context) }
    val controller = remember { SocialNetworkController(context) }
    val onboarding = remember { OnboardingState(context) }
    val language = remember { AppLanguageState(context) }
    val commentStore: CommentStore = controller.comments
    val followStore = remember { FollowStore(context) }
    val remoteCache = remember { RemoteProfileCache() }
    val media = remember { LocalMediaStore(context) }
    val contentFilter = remember { ContentFilter(context.applicationContext) }
    val appScope = rememberCoroutineScope()
    val mediaLoader = remember(media, controller) { MediaLoader(appScope, media, controller) }

    var setupDone by remember { mutableStateOf(false) }
    var widgetsEnabled by remember { mutableStateOf(true) }
    var setupError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { controller.start() }
    DisposableEffect(Unit) { onDispose { controller.stop() } }

    val ui by controller.ui.collectAsState()
    val hasKnownIdentity = ui.mainDht.isNotBlank()
    LaunchedEffect(ui.mainDht) {
        // The remote-profile cache is selected by what the previous account looked at, so it is
        // account-private even though each individual profile is public. Never carry it across.
        remoteCache.clear()
        state.ownerKey = ui.mainDht
    }

    // The encrypted profile itself is authoritative. The onboarding flag is useful metadata, but
    // it must never be able to send an established account through Create profile if a vault attach
    // races a reconnect. For a genuinely profile-less account we wait until BOTH the profile lookup
    // and onboarding lookup have finished before deciding that first-run is safe to show.
    LaunchedEffect(ui.mainDht, onboarding.ready, onboarding.completed, state.savedProfilePresent) {
        if (ui.mainDht.isBlank()) return@LaunchedEffect
        val profileAlreadyExists = state.savedProfilePresent == true
        setupDone = profileAlreadyExists
        if (profileAlreadyExists && !onboarding.completed) {
            // Repair old/missing onboarding metadata off the Compose thread.
            withContext(Dispatchers.IO) { onboarding.completed = true }
        }
    }
    val profilePresenceKnown = state.savedProfilePresent != null
    val safeToDecideFirstRun = state.savedProfilePresent == true ||
        (state.savedProfilePresent == false && onboarding.ready)
    val failure = ui.status.takeIf { it.startsWith("Error", ignoreCase = true) || it.contains("Could not bind") }
    val localizedResources = remember(language.current, context) {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language.current.code))
        context.createConfigurationContext(configuration).resources
    }

    CompositionLocalProvider(
        LocalAppLanguage provides language.current,
        LocalResources provides localizedResources,
        LocalContentFilter provides contentFilter,
    ) {
    MaterialTheme(colorScheme = DesignerColorScheme) {
        Surface(color = MaterialTheme.colorScheme.background) {
            when {
                !hasKnownIdentity || (!ui.connected && !setupDone) -> ConnectionGate(
                    status = ui.status,
                    error = failure,
                    onRetry = { controller.restart() }
                )

                !setupDone && (!profilePresenceKnown || !safeToDecideFirstRun) -> ConnectionGate(
                    status = "Loading your saved profile…",
                    error = state.persistError?.let { "Saved profile could not be read: $it" }
                        ?: onboarding.loadProblem?.let { "Profile setup state could not be read: $it" },
                    onRetry = { controller.restart() }
                )

                // If this account says setup was completed but the encrypted profile is genuinely
                // absent, do NOT silently overwrite it with the starter profile. Keep the user on a
                // recovery/retry gate so a temporary daemon/vault problem cannot become data loss.
                state.savedProfilePresent == false && onboarding.completed -> ConnectionGate(
                    status = "Your saved profile needs attention.",
                    error = "This VeilKnit account was already set up, but Weave could not find its saved profile. " +
                        "Retrying is safer than creating a replacement automatically.",
                    onRetry = { controller.restart() }
                )

                !setupDone -> FirstRunScreen(
                    ownKey = ui.mainDht,
                    error = setupError,
                    media = media,
                    language = language,
                ) { name, argb, blurb, image ->
                    state.replaceDocument(starterProfile(context, name, argb, blurb, image))
                    // Only move past setup once the profile is actually on disk. Marking
                    // onboarding complete after a failed write is what stranded people on
                    // the built-in default with no way back.
                    if (state.persistError == null) {
                        controller.setDiscoveryName(name)
                        controller.setDiscoveryDescription(deriveSearchDescription(state.doc))
                        onboarding.completed = true
                        setupDone = true
                        setupError = null
                    } else {
                        setupError = state.persistError
                    }
                }

                else -> WeaveShell(
                    state = state,
                    controller = controller,
                    commentStore = commentStore,
                    followStore = followStore,
                    remoteCache = remoteCache,
                    media = media,
                    mediaLoader = mediaLoader,
                    language = language,
                    widgetsEnabled = widgetsEnabled,
                    onWidgetsEnabledChange = { widgetsEnabled = it },
                    connectionAvailable = ui.connected,
                    connectionStatus = ui.status,
                    onRetryConnection = { controller.restart() },
                )
            }
        }
    }
    }
}

@Composable
private fun WeaveShell(
    state: EditorState,
    controller: SocialNetworkController,
    commentStore: CommentStore,
    followStore: FollowStore,
    remoteCache: RemoteProfileCache,
    media: LocalMediaStore,
    mediaLoader: MediaLoader,
    language: AppLanguageState,
    widgetsEnabled: Boolean,
    onWidgetsEnabledChange: (Boolean) -> Unit,
    connectionAvailable: Boolean,
    connectionStatus: String,
    onRetryConnection: () -> Unit,
) {
    val groupStore = controller.groups
    val nav = rememberWeaveNavState(
        initialTab = Tab.Me,
        initialMode = groupStore.preferredMode,
    )
    val ui by controller.ui.collectAsState()
    val context = LocalContext.current
    var pendingExternalUrl by remember { mutableStateOf<String?>(null) }
    val groupPostSubmissionUi = remember { mutableMapOf<String, GroupPostSubmissionUiState>() }

    LaunchedEffect(ui.mainDht) {
        // In-flight post status belongs to the signed-in identity. Do not carry it across accounts.
        groupPostSubmissionUi.clear()
    }

    fun openDetectedLink(link: DetectedLink) {
        when (link) {
            is DetectedLink.Group -> controller.resolveGroupLink(link.raw) { group ->
                group?.let { nav.push(Destination.Group(it.groupId)) }
                    ?: toast(context, "That group could not be resolved.")
            }
            is DetectedLink.Post -> {
                val groupLink = GroupLink(link.groupId, link.creatorRoot).encode()
                controller.resolveGroupLink(groupLink) { group ->
                    if (group != null) {
                        nav.push(Destination.GroupPost(group.groupId, link.conversationId))
                    } else {
                        toast(context, "That post's group could not be resolved.")
                    }
                }
            }
            is DetectedLink.Media -> nav.push(
                Destination.LinkedMedia(link.mediaType, link.recordKey, link.sha256)
            )
            is DetectedLink.Profile -> nav.push(Destination.Profile(link.mainDht))
            is DetectedLink.External -> pendingExternalUrl = link.raw
            is DetectedLink.Dht -> toast(
                context,
                "This is a raw DHT location. Use a Weave media/group/post link when possible."
            )
            is DetectedLink.UnknownInternal -> toast(context, "This Weave link is not recognized.")
        }
    }

    val groupRevision = groupStore.revision
    val profileModerationCount = remember(ui.commentRevision, ui.mainDht) {
        commentStore.quarantined(ui.mainDht).size
    }
    val groupModerationCount = remember(groupRevision) { groupStore.pendingActionCount() }
    val destination = nav.current

    LaunchedEffect(destination, nav.mode, nav.tab) {
        val destinationName = when (destination) {
            Destination.Home -> "Home"
            Destination.Search -> "Search"
            Destination.Me -> "Me"
            Destination.Activity -> "Activity"
            Destination.Editor -> "Editor"
            Destination.Settings -> "Settings"
            Destination.GroupModeration -> "GroupModeration"
            is Destination.Profile -> "Profile"
            is Destination.QuickEdit -> "QuickEdit"
            is Destination.Group -> "Group"
            is Destination.GroupPost -> "GroupPost"
            is Destination.LinkedMedia -> "LinkedMedia"
            is Destination.GroupEditor -> "GroupEditor"
        }
        val editorDetail = when (destination) {
            Destination.Editor -> " editor=advanced page=${state.pageIndex} doc=${state.documentDiagnosticId()}"
            is Destination.QuickEdit -> " editor=basic page=${destination.pageIndex} doc=${state.documentDiagnosticId()}"
            else -> ""
        }
        controller.diagnosticBreadcrumb(
            "NAV",
            "mode=${nav.mode.name} tab=${nav.tab.name} destination=$destinationName$editorDetail"
        )
    }

    BackHandler(enabled = true) { nav.pop() }

    // Both editors are full-bleed: no bottom bar competing with their own controls. Keep them
    // composed through a transient daemon reconnect so their local navigation/editor state survives.
    if (destination is Destination.Editor) {
        Box(Modifier.fillMaxSize()) {
            EditorScreen(
                state = state,
                onOpenBasic = { nav.replaceTop(Destination.QuickEdit(state.pageIndex)) },
                onBack = { nav.pop() },
            )
            if (!connectionAvailable) {
                TransientConnectionBanner(connectionStatus, onRetryConnection, Modifier.align(Alignment.TopCenter))
            } else {
                state.persistError?.let { message ->
                    StorageWarningBanner(message, state::clearPersistError, Modifier.align(Alignment.TopCenter))
                }
            }
        }
        return
    }
    if (destination is Destination.QuickEdit) {
        Box(Modifier.fillMaxSize()) {
            QuickEditScreen(
                state = state,
                media = media,
                controller = controller,
                pageIndex = destination.pageIndex,
                onOpenAdvanced = { nav.replaceTop(Destination.Editor) },
                onBack = { nav.pop() },
            )
            if (!connectionAvailable) {
                TransientConnectionBanner(connectionStatus, onRetryConnection, Modifier.align(Alignment.TopCenter))
            } else {
                state.persistError?.let { message ->
                    StorageWarningBanner(message, state::clearPersistError, Modifier.align(Alignment.TopCenter))
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (!connectionAvailable) {
            TransientConnectionBanner(connectionStatus, onRetryConnection)
        } else {
            state.persistError?.let { message -> StorageWarningBanner(message, state::clearPersistError) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (destination) {
                Destination.Home -> if (nav.mode == BrowseMode.Groups) {
                    GroupHomeScreen(
                        store = groupStore,
                        onOpen = { nav.push(Destination.Group(it)) },
                    )
                } else {
                    HomeScreen(
                        controller = controller,
                        followStore = followStore,
                        onOpen = { nav.push(Destination.Profile(it)) },
                    )
                }

                Destination.Search -> if (nav.mode == BrowseMode.Groups) {
                    GroupSearchScreen(
                        store = groupStore,
                        onOpen = { nav.push(Destination.Group(it)) },
                        onResolveLink = { link ->
                            controller.resolveGroupLink(link)
                        },
                    )
                } else {
                    SearchScreen(
                        controller = controller,
                        onOpen = { nav.push(Destination.Profile(it)) },
                    )
                }

                Destination.Me -> if (nav.mode == BrowseMode.Groups) {
                    GroupMeScreen(
                        store = groupStore,
                        ownKey = ui.mainDht,
                        onOpen = { nav.push(Destination.Group(it)) },
                        onCreate = { nav.push(Destination.GroupEditor(null)) },
                        onManage = { nav.push(Destination.GroupEditor(it)) },
                        onModeration = { nav.push(Destination.GroupModeration) },
                        onSettings = { nav.push(Destination.Settings) },
                    )
                } else {
                    MeScreen(
                        state = state,
                        controller = controller,
                        commentStore = commentStore,
                        media = media,
                        loader = mediaLoader,
                        ownKey = ui.mainDht,
                        onEdit = { page ->
                            if (state.preferAdvancedEditor) nav.push(Destination.Editor)
                            else nav.push(Destination.QuickEdit(page))
                        },
                        onSettings = { nav.push(Destination.Settings) },
                        onOpenLink = ::openDetectedLink,
                    )
                }

                Destination.Activity -> ActivityScreen(
                    store = commentStore,
                    ownMainDht = ui.mainDht,
                    externalRevision = ui.commentRevision,
                    onKeep = { id -> controller.keepComment(id) },
                    onDrop = { id -> controller.dropComment(id) },
                )

                is Destination.Group -> {
                    val group = groupStore.byId(destination.groupId)
                    val continuity = controller.groupAuthorityContinuity(destination.groupId)
                    GroupDetailScreen(
                        store = groupStore,
                        media = media,
                        groupId = destination.groupId,
                        ownMainDht = ui.mainDht,
                        ownDisplayName = state.doc.profileName,
                        submissionUi = groupPostSubmissionUi.getOrPut(destination.groupId) { GroupPostSubmissionUiState() },
                        authorityContinuity = continuity,
                        onBack = { nav.pop() },
                        onOpenPost = { conversationId ->
                            nav.push(Destination.GroupPost(destination.groupId, conversationId))
                        },
                        onRefreshBranch = { branch ->
                            // GroupRuntime already commits the verified header/group/Pulse before
                            // this callback runs; do not immediately serialize the same state again.
                            controller.refreshGroupBranch(branch)
                        },
                        onJoin = {
                            when (group?.policy?.join) {
                                GroupJoinPolicy.Open -> groupStore.setJoined(destination.groupId, true)
                                GroupJoinPolicy.Request -> controller.requestGroupJoin(destination.groupId)
                                GroupJoinPolicy.Invite, null -> Unit
                            }
                        },
                        onLeave = { groupStore.setJoined(destination.groupId, false) },
                        onClaimModeration = { onResult ->
                            controller.claimGroup(
                                destination.groupId,
                                onClaimed = { claim ->
                                    // claim() already records/selects the new branch. Refresh it for
                                    // a current Pulse without repeating those encrypted state writes.
                                    controller.refreshGroupBranch(claim.pointer())
                                },
                                onResult = onResult,
                            )
                        },
                        branchOwnerName = controller::groupAuthorityName,
                        onSelectBranch = controller::noteGroupBranchSelected,
                        onCreatePost = { title, body, image, audio, onUploadDone ->
                            controller.createGroupPost(
                                groupId = destination.groupId,
                                title = title,
                                body = body,
                                authorName = state.doc.profileName,
                                media = media,
                                image = image,
                                audio = audio,
                            ) { ok, conversationId ->
                                onUploadDone(ok, conversationId)
                                if (ok) {
                                    groupStore.selectedBranch(destination.groupId)?.let { branch ->
                                        controller.refreshGroupBranch(branch) { _, pulse ->
                                            if (nav.current == destination && conversationId != null &&
                                                pulse?.conversations?.any { it.conversation.objectId == conversationId } == true) {
                                                nav.push(Destination.GroupPost(destination.groupId, conversationId))
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        onSetFeatured = { slot ->
                            controller.setGroupFeatured(destination.groupId, slot)
                        },
                        onAddModerator = { grant ->
                            controller.grantGroupModerator(destination.groupId, grant)
                        },
                        onLoadCuratorPosts = { done ->
                            controller.loadGroupCuratorPosts(destination.groupId, done)
                        },
                        onLoadMessage = { ref, done ->
                            controller.loadGroupMessage(ref, done)
                        },
                        onDeletePost = { conversationId, ref, reason, done ->
                            controller.deleteGroupPost(destination.groupId, conversationId, ref, reason, done)
                        },
                        onModifyPost = { conversationId, ref, title, body, done ->
                            controller.modifyGroupPost(destination.groupId, conversationId, ref, title, body, done)
                        },
                        onBanAuthor = { authorMainDht, done ->
                            controller.banGroupAuthor(destination.groupId, authorMainDht, done)
                        },
                    )
                }

                is Destination.GroupPost -> GroupPostScreen(
                    store = groupStore,
                    media = media,
                    controller = controller,
                    groupId = destination.groupId,
                    conversationId = destination.conversationId,
                    ownMainDht = ui.mainDht,
                    onBack = { nav.pop() },
                    onRefresh = {
                        controller.refreshGroupPost(destination.groupId, destination.conversationId)
                    },
                    onPostComment = { body ->
                        controller.postGroupComment(
                            groupId = destination.groupId,
                            conversationId = destination.conversationId,
                            body = body,
                            authorName = state.doc.profileName,
                        ) { ok ->
                            if (ok) {
                                controller.refreshGroupPost(destination.groupId, destination.conversationId)
                            }
                        }
                    },
                    onPinComment = { postId, pinned ->
                        controller.pinGroupComment(destination.groupId, postId, pinned)
                        controller.refreshGroupPost(destination.groupId, destination.conversationId)
                    },
                    onSetFeatured = { slot ->
                        controller.setGroupFeatured(destination.groupId, slot)
                    },
                    onOpenLink = ::openDetectedLink,
                )

                is Destination.LinkedMedia -> LinkedMediaScreen(
                    type = destination.type,
                    recordKey = destination.recordKey,
                    sha256 = destination.sha256,
                    media = media,
                    controller = controller,
                    onBack = { nav.pop() },
                )

                is Destination.GroupEditor -> GroupEditorScreen(
                    store = groupStore,
                    media = media,
                    ownKey = ui.mainDht,
                    groupId = destination.groupId,
                    onPublish = { group, pulse ->
                        controller.publishGroup(group, pulse) { published ->
                            groupStore.updateGroup(published)
                        }
                    },
                    onSaved = { id -> nav.replaceTop(Destination.Group(id)) },
                    onBack = { nav.pop() },
                )

                Destination.GroupModeration -> GroupModerationScreen(
                    store = groupStore,
                    onBack = { nav.pop() },
                    onResolve = { taskId, approve -> controller.resolveGroupModerationTask(taskId, approve) },
                )

                Destination.Settings -> SettingsScreen(
                    controller = controller,
                    ownKey = ui.mainDht,
                    media = media,
                    language = language,
                    widgetsEnabled = widgetsEnabled,
                    onWidgetsEnabledChange = onWidgetsEnabledChange,
                    activityCount = profileModerationCount,
                    onActivity = { nav.push(Destination.Activity) },
                    onClaimGroupLink = { link, onResult ->
                        controller.claimGroupLink(link, onResult)
                    },
                    onBack = { nav.pop() },
                )

                is Destination.Profile -> ProfileDestination(
                    mainDht = destination.mainDht,
                    controller = controller,
                    commentStore = commentStore,
                    followStore = followStore,
                    remoteCache = remoteCache,
                    mediaLoader = mediaLoader,
                    media = media,
                    state = state,
                    ownKey = ui.mainDht,
                    ownName = state.doc.profileName,
                    groupStore = groupStore,
                    onOpenGroup = { nav.push(Destination.Group(it)) },
                    onNavigate = { nav.replaceTop(Destination.Profile(it)) },
                    onOpenLink = ::openDetectedLink,
                    onBack = { nav.pop() },
                )

                Destination.Editor, is Destination.QuickEdit -> Unit // handled above
            }
        }
        WeaveBottomBar(
            nav = nav,
            groupModerationCount = groupModerationCount,
            onModeChanged = { groupStore.preferredMode = it },
        )
    }

    pendingExternalUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { pendingExternalUrl = null },
            title = { Text("Open external link?") },
            text = {
                Column {
                    Text("This link leaves Weave and opens another app or browser.")
                    Text(
                        url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    pendingExternalUrl = null
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure {
                        toast(context, "No app could open that link.")
                    }
                }) { Text("Open browser") }
            },
            dismissButton = {
                TextButton(onClick = { pendingExternalUrl = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StorageWarningBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 4.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("Profile storage needs attention"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(message, style = MaterialTheme.typography.labelSmall, maxLines = 2)
            }
            TextButton(onClick = onDismiss) { Text(tr("Dismiss")) }
        }
    }
}

@Composable
private fun TransientConnectionBanner(
    status: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 4.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("Reconnecting to VeilKnit…"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    status.ifBlank { tr("Your current screen and unsaved editor changes are being kept in memory.") },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
            TextButton(onClick = onRetry) { Text(tr("Retry")) }
        }
    }
}

@Composable
private fun ProfileDestination(
    mainDht: String,
    controller: SocialNetworkController,
    commentStore: CommentStore,
    followStore: FollowStore,
    remoteCache: RemoteProfileCache,
    mediaLoader: MediaLoader,
    media: LocalMediaStore,
    state: EditorState,
    ownKey: String,
    ownName: String,
    groupStore: GroupStore,
    onOpenGroup: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onOpenLink: (DetectedLink) -> Unit,
    onBack: () -> Unit,
) {
    val ui by controller.ui.collectAsState()
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    val remoteWidgetLoader = remember(controller) { RemoteWidgetLoader(controller) }
    val groupRevision = groupStore.revision
    val profileGroups = remember(groupRevision, mainDht) { groupStore.directoryFor(mainDht) }
    var following by remember(mainDht) { mutableStateOf(followStore.isFollowing(mainDht)) }

    // The decorated profile can come entirely from RemoteProfileCache. Group ownership/claims are
    // separate lightweight DHT metadata and must still be refreshed whenever this profile opens.
    LaunchedEffect(mainDht) {
        controller.refreshUserGroups(mainDht)
    }

    // The swipe queue is whatever list the user opened from, in its current order.
    val queue = remember(ui.recent) { ui.recent.map { it.hint.mainDht } }
    val position = queue.indexOf(mainDht)
    val hintName = ui.recent.firstOrNull { it.hint.mainDht == mainDht }?.hint?.name.orEmpty()

    // The blob may still be in flight when this destination opens, so poll rather than
    // resolving once during composition.
    // Seeded from the cache so returning to a tab shows the profile immediately. Starting at
    // null meant the spinner reappeared on every visit even though nothing had to be fetched.
    val cached = remember(mainDht) { remoteCache.get(mainDht) }
    val loaded by produceState<Result<RemoteProfile>?>(
        initialValue = cached?.let { Result.success(it) },
        mainDht,
    ) {
        remoteCache.get(mainDht)?.let { value = Result.success(it); return@produceState }
        controller.ensureProfileAvailable(mainDht)
        repeat(60) {
            val text = controller.profileText(mainDht)
            if (text != null) {
                value = runCatching { RemoteProfile.decode(mainDht, text, hintName) }
                    .onSuccess(remoteCache::put)
                return@produceState
            }
            delay(500)
        }
        value = Result.failure(IllegalStateException("Timed out waiting for this profile to arrive."))
    }

    val profile = loaded?.getOrNull()
    val failure = loaded?.exceptionOrNull()?.message

    when {
        profile != null -> ProfileViewerScreen(
            profile = profile,
            ownKey = ownKey,
            ownName = ownName,
            loader = mediaLoader,
            media = media,
            controller = controller,
            commentStore = commentStore,
            openComments = true,
            commentPolicy = controller.commentPolicyFor(mainDht),
            commentRevision = ui.commentRevision,
            onPostComment = { pageKey, body, openMode, replyTo ->
                controller.postComment(pageKey, body, openMode, replyTo)
            },
            onRetryComment = { id -> controller.retryComment(id) },
            onSyncComments = { pageKey -> controller.syncComments(mainDht, pageKey) },
            onSaveImageToGallery = { element ->
                val ok = media.exportToGallery(context, element.mediaContentHash, element.mediaTitle.ifBlank { "image" })
                toast(context, if (ok) translateUi("Saved to your gallery", appLanguage) else translateUi("Couldn't save that image", appLanguage))
            },
            onCopyImageLocation = { element ->
                val link = encodeMediaLink(
                    WeaveObjectRef(
                        objectId = element.mediaContentHash,
                        recordKey = element.mediaRecordKey,
                        type = WeaveObjectType.Image,
                    )
                )
                if (link == null) {
                    toast(context, translateUi("This image does not have a DHT location yet.", appLanguage))
                } else {
                    copyToClipboard(context, "Weave image", link)
                    toast(context, translateUi("Image link copied", appLanguage))
                }
            },
            onSaveImageToEditor = { element ->
                media.saveToLibrary(
                    element.mediaContentHash,
                    element.intrinsicWidth.toInt().coerceAtLeast(1),
                    element.intrinsicHeight.toInt().coerceAtLeast(1),
                    element.mediaDescription,
                )
                toast(context, translateUi("Saved to your editor", appLanguage))
            },
            onSaveProfileCopy = { remote ->
                val text = runCatching { ProfileCodec.encodeText(remote.document) }.getOrNull()
                val ok = text != null && state.saveImportedCopy(remote.displayName, text)
                toast(context, if (ok) translateUi("Saved a copy you can open in the editor", appLanguage) else translateUi("Couldn't save that profile", appLanguage))
            },
            onCopyProfileKey = { remote ->
                copyToClipboard(context, "Profile key", remote.mainDht)
                toast(context, translateUi("Copied", appLanguage))
            },
            profileGroups = profileGroups,
            onOpenGroup = onOpenGroup,
            onOpenLink = onOpenLink,
            isFollowing = following,
            onToggleFollow = { following = followStore.toggle(mainDht) },
            onPrevProfile = if (position > 0) ({ onNavigate(queue[position - 1]) }) else null,
            onNextProfile = if (position >= 0 && position < queue.lastIndex) ({ onNavigate(queue[position + 1]) }) else null,
            onBack = onBack,
            widgetLoader = remoteWidgetLoader,
        )

        else -> Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (failure == null) {
                CircularProgressIndicator()
                Text(tr("Fetching this profile…"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
            } else {
                Text(tr("Couldn't open this profile"), style = MaterialTheme.typography.titleSmall)
                Text(
                    failure,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            TextButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text(tr("Back")) }
        }
    }
}

private fun copyToClipboard(context: android.content.Context, label: String, value: String) {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
}

private fun toast(context: android.content.Context, message: String) {
    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
private fun WeaveBottomBar(
    nav: WeaveNavState,
    groupModerationCount: Int,
    onModeChanged: (BrowseMode) -> Unit,
) {
    val groupsMode = nav.mode == BrowseMode.Groups
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.CenterVertically) {
            BottomTabButton(
                nav = nav,
                tab = Tab.Home,
                iconRes = if (groupsMode) R.drawable.nav_snapshot else R.drawable.nav_home,
                contentDescription = if (groupsMode) "Snapshot" else "Home",
            )
            BottomTabButton(
                nav = nav,
                tab = Tab.Search,
                iconRes = R.drawable.nav_search,
                contentDescription = "Search",
            )

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable {
                        val next = nav.toggleMode()
                        onModeChanged(next)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(if (groupsMode) R.drawable.nav_groups else R.drawable.nav_people),
                    contentDescription = if (groupsMode) "Groups" else "People",
                    modifier = Modifier.size(40.dp),
                )
            }

            BottomTabButton(
                nav = nav,
                tab = Tab.Me,
                iconRes = if (groupsMode) R.drawable.nav_curator else R.drawable.nav_me,
                contentDescription = if (groupsMode) "Curator" else "Me",
                badge = if (groupsMode) groupModerationCount else 0,
            )
        }
    }
}

@Composable
private fun RowScope.BottomTabButton(
    nav: WeaveNavState,
    tab: Tab,
    iconRes: Int,
    contentDescription: String,
    badge: Int = 0,
) {
    val selected = nav.tab == tab
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable { nav.selectTab(tab) },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp).fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(iconRes),
                    contentDescription = contentDescription,
                    modifier = Modifier.size(40.dp).alpha(if (selected) 1f else .58f),
                )
            }
        }
        if (badge > 0) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 18.dp),
            ) {
                Text(
                    if (badge > 99) "99+" else badge.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}
