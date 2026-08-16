package com.veilysocial.profiledesigner

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Root of the app.
 *
 * The social surface is the shell and the editor is a destination inside it — the reverse
 * of the original arrangement, where a designer with nine tools was the first thing a new
 * user saw. First run still lands on Me, because on day one there is nothing else to look at.
 */
@Composable
fun VeilyApp() {
    val context = LocalContext.current
    val state = remember { EditorState(context) }
    val controller = remember { SocialNetworkController(context) }
    val onboarding = remember { OnboardingState(context) }
    val commentStore: CommentStore = remember { LocalCommentStore(context) }
    val followStore = remember { FollowStore(context) }
    val remoteCache = remember { RemoteProfileCache() }
    val media = remember { LocalMediaStore(context) }
    val appScope = rememberCoroutineScope()
    val mediaLoader = remember(media, controller) { MediaLoader(appScope, media, controller) }

    var setupDone by remember { mutableStateOf(onboarding.completed) }
    var widgetsEnabled by remember { mutableStateOf(true) }
    var setupError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { controller.start() }
    DisposableEffect(Unit) { onDispose { controller.stop() } }

    val ui by controller.ui.collectAsState()
    val connected = ui.mainDht.isNotBlank()
    LaunchedEffect(ui.mainDht) { state.ownerKey = ui.mainDht }
    val failure = ui.status.takeIf { it.startsWith("Error", ignoreCase = true) || it.contains("Could not bind") }

    MaterialTheme(colorScheme = DesignerColorScheme) {
        Surface(color = MaterialTheme.colorScheme.background) {
            when {
                !connected -> ConnectionGate(
                    status = ui.status,
                    error = failure,
                    onRetry = { controller.restart() }
                )

                !setupDone -> FirstRunScreen(ownKey = ui.mainDht, error = setupError) { name, argb, blurb ->
                    state.replaceDocument(starterProfile(context, name, argb, blurb))
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

                else -> VeilyShell(
                    state = state,
                    controller = controller,
                    commentStore = commentStore,
                    followStore = followStore,
                    remoteCache = remoteCache,
                    media = media,
                    mediaLoader = mediaLoader,
                    widgetsEnabled = widgetsEnabled,
                    onWidgetsEnabledChange = { widgetsEnabled = it },
                )
            }
        }
    }
}

@Composable
private fun VeilyShell(
    state: EditorState,
    controller: SocialNetworkController,
    commentStore: CommentStore,
    followStore: FollowStore,
    remoteCache: RemoteProfileCache,
    media: LocalMediaStore,
    mediaLoader: MediaLoader,
    widgetsEnabled: Boolean,
    onWidgetsEnabledChange: (Boolean) -> Unit,
) {
    val nav = rememberVeilyNavState(initialTab = Tab.Me)
    val ui by controller.ui.collectAsState()
    val destination = nav.current

    BackHandler(enabled = true) { nav.pop() }

    // Both editors are full-bleed: no bottom bar competing with their own controls.
    if (destination is Destination.Editor) {
        EditorScreen(state, onBack = { nav.pop() })
        return
    }
    if (destination is Destination.QuickEdit) {
        QuickEditScreen(
            state = state,
            media = media,
            pageIndex = destination.pageIndex,
            onOpenAdvanced = { nav.replaceTop(Destination.Editor) },
            onBack = { nav.pop() },
        )
        return
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (destination) {
                Destination.Home -> HomeScreen(
                    controller = controller,
                    followStore = followStore,
                    onOpen = { nav.push(Destination.Profile(it)) }
                )

                Destination.Search -> SearchScreen(
                    controller = controller,
                    onOpen = { nav.push(Destination.Profile(it)) }
                )

                Destination.Me -> MeScreen(
                    state = state,
                    controller = controller,
                    commentStore = commentStore,
                    media = media,
                    loader = mediaLoader,
                    ownKey = ui.mainDht,
                    onQuickEdit = { page -> nav.push(Destination.QuickEdit(page)) },
                    onAdvancedEdit = { nav.push(Destination.Editor) },
                    onSettings = { nav.push(Destination.Settings) },
                )

                Destination.Activity -> ActivityScreen(store = commentStore, ownMainDht = ui.mainDht)

                Destination.Settings -> SettingsScreen(
                    controller = controller,
                    ownKey = ui.mainDht,
                    widgetsEnabled = widgetsEnabled,
                    onWidgetsEnabledChange = onWidgetsEnabledChange,
                    onBack = { nav.pop() },
                )

                is Destination.Profile -> ProfileDestination(
                    mainDht = destination.mainDht,
                    controller = controller,
                    commentStore = commentStore,
                    followStore = followStore,
                    remoteCache = remoteCache,
                    mediaLoader = mediaLoader,
                    ownKey = ui.mainDht,
                    ownName = state.doc.profileName,
                    onNavigate = { nav.replaceTop(Destination.Profile(it)) },
                    onBack = { nav.pop() },
                )

                Destination.Editor, is Destination.QuickEdit -> Unit // handled above
            }
        }
        VeilyBottomBar(nav)
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
    ownKey: String,
    ownName: String,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
) {
    val ui by controller.ui.collectAsState()
    var following by remember(mainDht) { mutableStateOf(followStore.isFollowing(mainDht)) }

    // The swipe queue is whatever list the user opened from, in its current order.
    val queue = remember(ui.recent) { ui.recent.map { it.hint.mainDht } }
    val position = queue.indexOf(mainDht)
    val hintName = ui.recent.firstOrNull { it.hint.mainDht == mainDht }?.hint?.name.orEmpty()

    // The blob may still be in flight when this destination opens, so poll rather than
    // resolving once during composition.
    val loaded by produceState<Result<RemoteProfile>?>(initialValue = null, mainDht) {
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
            commentStore = commentStore,
            openComments = true,
            isFollowing = following,
            onToggleFollow = { following = followStore.toggle(mainDht) },
            onPrevProfile = if (position > 0) ({ onNavigate(queue[position - 1]) }) else null,
            onNextProfile = if (position >= 0 && position < queue.lastIndex) ({ onNavigate(queue[position + 1]) }) else null,
            onBack = onBack,
        )

        else -> Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (failure == null) {
                CircularProgressIndicator()
                Text("Fetching this profile\u2026", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
            } else {
                Text("Couldn't open this profile", style = MaterialTheme.typography.titleSmall)
                Text(
                    failure,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            TextButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("Back") }
        }
    }
}

@Composable
private fun VeilyBottomBar(nav: VeilyNavState) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
            Tab.values().forEach { tab ->
                val selected = nav.tab == tab
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { nav.selectTab(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        tab.glyph,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
