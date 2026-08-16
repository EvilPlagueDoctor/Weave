package com.veilysocial.profiledesigner

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Remembers whether setup has been completed, so first run happens exactly once. */
class OnboardingState(context: Context) {
    private val prefs = context.getSharedPreferences("veily_onboarding", Context.MODE_PRIVATE)

    var completed: Boolean
        get() = prefs.getBoolean("completed", false)
        set(value) { prefs.edit().putBoolean("completed", value).apply() }
}

/**
 * Shown while the daemon connection is being established. The daemon takes roughly 90
 * seconds from cold launch to READY, so this screen exists to be honest about waiting
 * rather than to hide it.
 */
@Composable
fun ConnectionGate(status: String, error: String?, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("VeilySocial", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        if (error == null) {
            CircularProgressIndicator()
            Spacer(Modifier.height(20.dp))
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Connecting to the VeilKnit daemon. A cold start takes a minute or two while the node finds peers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
        } else {
            Text("Can't reach the daemon", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = 20.dp)) { Text("Try again") }
        }
    }
}

private val StarterColors = listOf(
    0xFFF7F7F7, 0xFFFFE9EC, 0xFFFFF3D6, 0xFFE6F4EA,
    0xFFE3F0FB, 0xFFEDE7F6, 0xFF2B2D31, 0xFF1B3A2F,
).map { it.toInt() }

/**
 * First run. Name and a background colour, nothing more.
 *
 * No default blurb is generated: ten thousand identical "Hello, I'm new here" profiles
 * would all be duplicates under the duplicate penalty and would collapse into one
 * indistinguishable MinHash signature.
 */
@Composable
fun FirstRunScreen(
    ownKey: String,
    error: String? = null,
    onDone: (name: String, backgroundArgb: Int, blurb: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var blurb by remember { mutableStateOf("") }
    var colorArgb by remember { mutableIntStateOf(StarterColors.first()) }
    val scroll = rememberScrollState()

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(scroll).padding(24.dp)
    ) {
        Text("Set up your profile", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "This is a persona, not your real name. You can make another one any time, and nothing here is tied to who you are.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
        )

        error?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Couldn't save that profile", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(message, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Identicon(ownKey, size = 56.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Your avatar", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Generated from your key, so nobody else can produce it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(60) },
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            label = { Text("Display name") },
            singleLine = true,
        )

        Text("Page colour", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StarterColors.forEach { argb ->
                val selected = argb == colorArgb
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .clickable { colorArgb = argb }
                ) {
                    if (selected) {
                        Box(
                            Modifier.fillMaxSize().padding(4.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = .35f))
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = blurb,
            onValueChange = { blurb = it.take(400) },
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            label = { Text("A line about you (optional)") },
            minLines = 3,
        )
        Text(
            "Left blank is fine. Whatever you write here is public and is what discovery matches on.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )

        Button(
            onClick = { onDone(name.trim(), colorArgb, blurb.trim()) },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 28.dp)
        ) { Text("Create my profile") }

        Text(
            "Your profile isn't published until you choose to publish it.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

/**
 * Builds the starter document from the first-run answers.
 *
 * Two deliberate restrictions. Nothing instructional is written into the document: guidance
 * copy would be published verbatim by every new user, which is duplicate content under the
 * duplicate penalty and collapses their signatures together. And every element is a plain
 * text block, so the simple editor can round-trip the result without its "this page uses the
 * advanced editor" warning firing on a profile the person has not even opened yet.
 *
 * What it does demonstrate is structure: a second page, so the book model is visible from
 * the first minute rather than being a feature nobody discovers.
 */
fun starterProfile(context: Context, name: String, backgroundArgb: Int, blurb: String): ProfileDocument {
    val doc = makeDefaultProfile()
    doc.profileName = name

    val home = doc.pages.firstOrNull() ?: return doc
    home.name = context.getString(R.string.default_page_name)
    home.root.background = BackgroundSpec(kind = BackgroundKind.Solid, solidArgb = backgroundArgb)

    val homeBlocks = buildList {
        add(QuickBlock.Heading(makeId("head"), name))
        if (blurb.isNotBlank()) add(QuickBlock.Body(makeId("text"), blurb))
    }
    applyBlocksToPage(home, homeBlocks, STARTER_REFERENCE_WIDTH, 1f)

    val projects = Page(
        id = makeId("page"),
        name = "Projects",
        aspectRatio = home.aspectRatio,
        root = Element(
            type = ElementType.Block,
            id = makeId("root"),
            name = "Projects",
            rect = RectSpec(0f, 0f, 1f, 1f),
            background = BackgroundSpec(kind = BackgroundKind.Solid, solidArgb = backgroundArgb),
        )
    )
    applyBlocksToPage(projects, listOf(QuickBlock.Heading(makeId("head"), "Projects")), STARTER_REFERENCE_WIDTH, 1f)
    doc.pages.add(projects)

    return doc
}

/**
 * Nominal width used to lay out the starter pages before a real viewport exists.
 *
 * The layout is scale invariant: width and font size are both multiplied by the density, so
 * StaticLayout wraps at the same line count and the resulting fractions are identical at any
 * real density. Only the physical width the person eventually views at can shift the wrap.
 */
private const val STARTER_REFERENCE_WIDTH = 400f

private fun isDarkArgb(argb: Int): Boolean {
    val r = (argb shr 16) and 0xff
    val g = (argb shr 8) and 0xff
    val b = argb and 0xff
    return (0.299 * r + 0.587 * g + 0.114 * b) < 140
}

/** The Me tab: your own book, with edit and publish as actions on it. */
@Composable
fun MeScreen(
    state: EditorState,
    controller: SocialNetworkController,
    commentStore: CommentStore,
    media: LocalMediaStore,
    loader: MediaLoader,
    ownKey: String,
    onQuickEdit: (Int) -> Unit,
    onAdvancedEdit: () -> Unit,
    onSettings: () -> Unit,
) {
    val ui by controller.ui.collectAsState()
    val scope = rememberCoroutineScope()
    var publishError by remember { mutableStateOf<String?>(null) }
    var showingLive by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var pageIndex by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()

    // What everyone else currently sees, decoded from the last published payload.
    val livePublished = remember(ui.profileRoot, ui.mainDht, showingLive) {
        if (!showingLive) null
        else controller.profileText(ui.mainDht)?.let { text ->
            runCatching { ProfileCodec.decodeText(text) }.getOrNull()
        }
    }
    val shownDoc = livePublished ?: state.doc
    val shownPage = shownDoc.pages.getOrNull(pageIndex.coerceAtMost(shownDoc.pages.lastIndex))
        ?: shownDoc.pages.first()
    val mediaLookup = remember(loader) { { e: Element -> loader.lookup(e) } }

    LaunchedEffect(shownDoc, shownPage.id) { loader.setWanted(shownDoc.imageHashes()) }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable { onSettings() }) {
                    Identicon(ownKey, size = 34.dp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.doc.profileName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(
                        if (ui.profileRoot.isBlank()) "Not published yet" else "Published",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onAdvancedEdit, enabled = !showingLive) { Text("Advanced") }
                TextButton(onClick = { onQuickEdit(pageIndex) }, enabled = !showingLive) { Text("Edit") }
                Button(
                    enabled = !ui.publishing && uploadProgress == null,
                    onClick = {
                        // Discovery shows the profile name, so keep the two from drifting:
                        // renaming a profile should rename it in search too.
                        controller.setDiscoveryName(state.doc.profileName)
                        // The blurb is no longer typed anywhere: it comes off the top of the
                        // page, so what ranks in search is what the page actually says.
                        controller.setDiscoveryDescription(deriveSearchDescription(state.doc))
                        publishError = null
                        scope.launch {
                            // Images first: the payload records their record keys, so
                            // publishing the page before its pictures exist would announce a
                            // document full of holes.
                            val uploaded = publishPendingMedia(state.doc, media, controller) { done, total ->
                                uploadProgress = done to total
                            }
                            uploadProgress = null
                            val uploadFailure = uploaded.exceptionOrNull()
                            if (uploadFailure != null) {
                                publishError = uploadFailure.message ?: "Couldn't upload the images."
                                return@launch
                            }
                            // encodeText throws on an invalid document, and this would
                            // otherwise escape as a crash rather than an error.
                            val encoded = runCatching { state.ownProfileTextForPublish() }
                            val failure = encoded.exceptionOrNull()
                            when {
                                failure != null ->
                                    publishError = failure.message ?: "This profile can't be published yet."
                                !state.persistActive() ->
                                    publishError = state.persistError ?: "Couldn't save this profile."
                                else ->
                                    controller.publishProfile(encoded.getOrThrow(), state.ownProfileNameForPublish())
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 14.dp)
                ) {
                    val progress = uploadProgress
                    if (progress != null) {
                        CircularProgressIndicator(
                            Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Images ${progress.first}/${progress.second}")
                    } else if (ui.publishing) {
                        CircularProgressIndicator(
                            Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Publishing")
                    } else {
                        Text("Publish")
                    }
                }
            }
        }
        DraftLiveSwitch(
            showingLive = showingLive,
            published = ui.profileRoot.isNotBlank(),
            onSelect = { showingLive = it },
        )
        publishError?.let { message ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Couldn't publish", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text(message, style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { publishError = null }) { Text("Dismiss") }
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val canvasWidth = maxWidth
            val canvasHeight = canvasWidth / shownPage.aspectRatio.coerceIn(MIN_PAGE_ASPECT, MAX_PAGE_ASPECT)
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Surface(color = Color.White) {
                    PageCanvas(
                        shownPage,
                        Modifier.fillMaxWidth().height(canvasHeight),
                        widgetLookup = state::widgetProgramFor,
                        imageLookup = mediaLookup,
                        ownerKey = ownKey,
                    )
                }
                PageRail(
                    pageNames = shownDoc.pages.map { it.name },
                    index = pageIndex.coerceAtMost(shownDoc.pages.lastIndex),
                    onSelect = { pageIndex = it },
                    onPrev = { if (pageIndex > 0) pageIndex-- },
                    onNext = { if (pageIndex < shownDoc.pages.lastIndex) pageIndex++ },
                )
                CommentsSection(
                    pageKey = pageKeyOf(ownKey, shownPage.id),
                    pageOwnerKey = ownKey,
                    store = commentStore,
                    ownKey = ownKey,
                    ownName = state.doc.profileName,
                    openMode = true,
                    onPosted = {},
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Draft is the document being edited; Live is the payload other people are currently
 * fetching. They diverge the moment an edit is made and only reconverge on publish, so the
 * comparison needs to be one tap rather than a second device.
 */
@Composable
private fun DraftLiveSwitch(showingLive: Boolean, published: Boolean, onSelect: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(false to "Draft", true to "Live").forEach { (live, label) ->
                val selected = live == showingLive
                val enabled = !live || published
                Surface(
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f),
                        modifier = Modifier
                            .clickable(enabled = enabled) { onSelect(live) }
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }
            Text(
                when {
                    !published -> "Nothing published yet"
                    showingLive -> "What everyone else sees"
                    else -> "Your unpublished changes"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SettingsScreen(
    controller: SocialNetworkController,
    ownKey: String,
    widgetsEnabled: Boolean,
    onWidgetsEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val ui by controller.ui.collectAsState()
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("\u2190") }
                Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp)) {
            Text("Widgets", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allow widgets to run", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Widgets never run on their own — you always tap to start one. Turn this off and they stay as static pictures.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = widgetsEnabled, onCheckedChange = onWidgetsEnabledChange)
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("This identity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Identicon(ownKey, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(ui.discoveryName.ifBlank { "Unnamed" }, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Peers ${ui.peers} \u00B7 verified ${ui.verified}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "Reusing images or widgets across identities links them: identical bytes have an identical hash no matter who publishes them.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("Discovery", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "The blurb people see beside your name in search comes from the text at the top of your home page, so it always matches what is actually there. What you search for and what you avoid stays on this device and is never published.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            OutlinedTextField(
                value = ui.featuresText,
                onValueChange = controller::setFeatures,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text("Interests, comma separated") },
                minLines = 2,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}
