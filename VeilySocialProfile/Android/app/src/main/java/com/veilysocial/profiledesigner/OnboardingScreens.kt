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
fun FirstRunScreen(ownKey: String, onDone: (name: String, backgroundArgb: Int, blurb: String) -> Unit) {
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

/** Builds the starter document from the first-run answers. */
fun starterProfile(context: Context, name: String, backgroundArgb: Int, blurb: String): ProfileDocument {
    val doc = makeDefaultProfile()
    doc.profileName = name
    val page = doc.pages.firstOrNull() ?: return doc
    page.name = context.getString(R.string.default_page_name)
    page.root.background = BackgroundSpec(kind = BackgroundKind.Solid, solidArgb = backgroundArgb)

    val dark = isDarkArgb(backgroundArgb)
    val textArgb = if (dark) 0xFFF5F5F5.toInt() else 0xFF111827.toInt()

    page.root.children.clear()
    page.root.children.add(
        Element(
            type = ElementType.Text,
            name = "Name",
            rect = RectSpec(x = .07f, y = .05f, width = .86f, height = .09f, zIndex = 1),
            text = name,
            fontSize = 30f,
            bold = true,
            textArgb = textArgb,
            textAlign = TextAlignMode.Left,
        )
    )
    if (blurb.isNotBlank()) {
        page.root.children.add(
            Element(
                type = ElementType.Text,
                name = "About",
                rect = RectSpec(x = .07f, y = .16f, width = .86f, height = .18f, zIndex = 2),
                text = blurb,
                fontSize = 16f,
                textArgb = textArgb,
                textAlign = TextAlignMode.Left,
            )
        )
    }
    return doc
}

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
    ownKey: String,
    onEdit: () -> Unit,
    onSettings: () -> Unit,
) {
    val ui by controller.ui.collectAsState()
    var pageIndex by remember { mutableIntStateOf(0) }
    val page = state.doc.pages.getOrNull(pageIndex) ?: state.doc.pages.first()
    val scroll = rememberScrollState()

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
                TextButton(onClick = onEdit) { Text("Edit") }
                Button(
                    onClick = { controller.publishProfile(state.ownProfileTextForPublish(), state.ownProfileNameForPublish()) },
                    contentPadding = PaddingValues(horizontal = 14.dp)
                ) { Text("Publish") }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val canvasWidth = maxWidth
            val canvasHeight = canvasWidth / page.aspectRatio.coerceIn(.15f, 6f)
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Surface(color = Color.White) {
                    PageCanvas(page, Modifier.fillMaxWidth().height(canvasHeight), widgetLookup = state::widgetProgramFor)
                }
                PageRail(
                    pageNames = state.doc.pages.map { it.name },
                    index = pageIndex,
                    onSelect = { pageIndex = it },
                    onPrev = { if (pageIndex > 0) pageIndex-- },
                    onNext = { if (pageIndex < state.doc.pages.lastIndex) pageIndex++ },
                )
                Spacer(Modifier.height(24.dp))
            }
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
                "What you search for and what you avoid stays on this device. It is never published.",
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
