package com.veilysocial.profiledesigner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.veilysocial.profiledesigner.backbone.ProfileHint
import com.veilysocial.profiledesigner.backbone.VerificationState

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
                Text("Recently found", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    state.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !followingOnly,
                        onClick = { followingOnly = false },
                        label = { Text("Everyone") }
                    )
                    FilterChip(
                        selected = followingOnly,
                        onClick = { followingOnly = true },
                        label = { Text("Following") }
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { controller.refreshNow() }) { Text("Refresh") }
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
            if (followingOnly) "You aren't following anyone yet." else "Nobody found yet.",
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            if (followingOnly) "Follow people from their page and they'll show up here."
            else "The network walk is still running. This can take a couple of minutes on a cold start.",
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
 * named person", which is a claim VeilySocial never makes. These describe how the record
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

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Search", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = state.nameQuery,
                    onValueChange = controller::setNameQuery,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.query,
                    onValueChange = controller::setQuery,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Interests, words on the page") },
                    minLines = 2,
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { controller.clearExamples() }) { Text("Clear examples") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { controller.search() }) { Text("Search") }
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
                            Text("More like this", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { controller.avoidLike(scored.hint.mainDht) }) {
                            Text("Less like this", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * Activity is about content YOU published. Everything here is visible to anyone who can see
 * that content — the moment something lands here that only you can see, it has become an
 * inbox, which is the thing this app deliberately does not have.
 */
@Composable
fun ActivityScreen(store: CommentStore, ownMainDht: String) {
    var revision by remember { mutableIntStateOf(0) }
    val held = remember(revision, ownMainDht) { store.quarantined(ownMainDht) }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Comments on your pages waiting on you. Nothing here is private — keeping one publishes it where the comment was left, and anything you ignore expires on its own.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        if (held.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Nothing waiting.", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Comments waiting on a decision show up here. Keep one to make it part of your page, or drop it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(held, key = { it.id }) { comment ->
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
                        }
                        Text(comment.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { store.setState(comment.id, CommentState.Dropped); revision++ }) { Text("Drop") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { store.setState(comment.id, CommentState.Accepted); revision++ }) { Text("Keep") }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
