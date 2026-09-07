package app.weave

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.weave.backbone.ProfileHint
import app.weave.backbone.VerificationState

/**
 * The list is the home surface, and the swipe deck is what happens once you open something
 * from it. That way the user picks the queue instead of being fed one.
 */
@Composable
fun HomeScreen(
    controller: SocialNetworkController,
    followStore: FollowStore,
    onOpen: (String) -> Unit,
) {
    val state by controller.ui.collectAsState()
    var followingOnly by remember { mutableStateOf(false) }
    val following = remember(followingOnly) { followStore.all() }

    val rows = remember(state.recent, followingOnly, following) {
        state.recent.filter { !followingOnly || it.hint.mainDht in following }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(tr("Recently found"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    state.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !followingOnly,
                        onClick = { followingOnly = false },
                        label = { Text(tr("Everyone")) }
                    )
                    FilterChip(
                        selected = followingOnly,
                        onClick = { followingOnly = true },
                        label = { Text(tr("Following")) }
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { controller.refreshNow() }) { Text(tr("Refresh")) }
                }
            }
        }

        if (rows.isEmpty()) {
            EmptyDiscovery(followingOnly)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.hint.mainDht }) { row ->
                    ProfileRow(
                        hint = row.hint,
                        enabled = true,
                        onClick = {
                            controller.ensureProfileAvailable(row.hint.mainDht)
                            onOpen(row.hint.mainDht)
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun EmptyDiscovery(followingOnly: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (followingOnly) tr("You aren't following anyone yet.") else tr("Nobody found yet."),
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            if (followingOnly) tr("Follow people from their page and they'll show up here.")
            else tr("The network walk is still running. This can take a couple of minutes on a cold start."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
fun ProfileRow(hint: ProfileHint, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Identicon(hint.mainDht, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                hint.name.ifBlank { "Untitled" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            if (hint.description.isNotBlank()) {
                Text(
                    hint.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            Text(
                provenanceLabel(hint.verification),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * Deliberately not a checkmark. Every checkmark convention online means "this is the real
 * named person", which is a claim Weave never makes. These describe how the record
 * reached you, which is what the tiers actually encode.
 */
fun provenanceLabel(state: VerificationState): String = when (state) {
    VerificationState.DHT_VERIFIED -> "Read from source"
    VerificationState.APP_ROOT_CONFIRMED -> "Confirmed at root"
    VerificationState.MULTI_SOURCE_HINT -> "Seen via several peers"
    VerificationState.GOSSIP_HINT -> "Heard via gossip"
}

@Composable
fun SearchScreen(controller: SocialNetworkController, onOpen: (String) -> Unit) {
    val state by controller.ui.collectAsState()
    val context = LocalContext.current
    var showStats by remember { mutableStateOf(false) }
    val report = remember(showStats, state.query, state.gossipSent, state.gossipReceived, state.recent) {
        if (showStats) controller.librarianReport(state.query) else ""
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(tr("Search"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = state.query,
                    onValueChange = controller::setQuery,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text(tr("Search profiles")) },
                    singleLine = true,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { controller.clearExamples() }) { Text(tr("Clear examples")) }
                    TextButton(onClick = { showStats = true }) { Text(tr("Librarian data")) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { controller.search() }) { Text(tr("Search")) }
                }
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(state.searchResults, key = { it.hint.mainDht }) { scored ->
                Column {
                    ProfileRow(
                        hint = scored.hint,
                        enabled = true,
                        onClick = {
                            controller.ensureProfileAvailable(scored.hint.mainDht)
                            onOpen(scored.hint.mainDht)
                        }
                    )
                    Row(Modifier.padding(start = 72.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { controller.moreLike(scored.hint.mainDht) }) {
                            Text(tr("More like this"), style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { controller.avoidLike(scored.hint.mainDht) }) {
                            Text(tr("Less like this"), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showStats) {
        AlertDialog(
            onDismissRequest = { showStats = false },
            title = { Text(tr("Librarian data")) },
            text = {
                Column(Modifier.heightIn(max = 430.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    Text(report, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Weave librarian data", report))
                }) { Text(tr("Copy to clipboard")) }
            },
            dismissButton = { TextButton(onClick = { showStats = false }) { Text(tr("Close")) } },
        )
    }
}

/**
 * Activity is about content YOU published. Everything here is visible to anyone who can see
 * that content — the moment something lands here that only you can see, it has become an
 * inbox, which is the thing this app deliberately does not have.
 */
@Composable
fun ActivityScreen(
    store: CommentStore,
    ownMainDht: String,
    externalRevision: Long,
    onKeep: (String) -> Unit,
    onDrop: (String) -> Unit,
) {
    var localRevision by remember { mutableIntStateOf(0) }
    val activity = remember(localRevision, externalRevision, ownMainDht) {
        store.activityForOwner(ownMainDht)
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(tr("Activity"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    tr("Comments received on your pages. Comments needing approval have Keep and Drop controls; published comments stay here as history."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        if (activity.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(tr("No comment activity yet."), style = MaterialTheme.typography.titleSmall)
                Text(
                    tr("Incoming comments will appear here, including ones that were automatically published."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(activity, key = { it.id }) { comment ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Identicon(comment.authorKey.ifBlank { comment.authorName }, size = 32.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(comment.authorName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    comment.pageKey.substringAfter('#'),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                when (comment.state) {
                                    CommentState.Accepted -> tr("Published")
                                    CommentState.Held -> tr("Needs approval")
                                    CommentState.Provisional -> tr("Awaiting confirmation")
                                    CommentState.Dropped -> tr("Dropped")
                                    CommentState.Sending -> tr("Sending")
                                    CommentState.Failed -> tr("Failed")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(comment.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                        if (comment.state == CommentState.Held) {
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { onDrop(comment.id); localRevision++ }) {
                                    Text(tr("Drop"))
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = { onKeep(comment.id); localRevision++ }) { Text(tr("Keep")) }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
