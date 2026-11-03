package app.weave

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Read-only event inspector. The signed, account-encrypted GroupEventStoreV2 remains authoritative.
 * It deduplicates by signed event ID (and separately flags conflicting bodies); this view keeps no
 * independent event-body database. No network fetch takes place until the user opens a record.
 *
 * Non-group network actions are shown in a separate diagnostic tab rather than pretending that
 * an RPC is a group message. That tab is metadata-only; it never captures or displays tokens.
 */
@Composable
fun EventMonitorScreen(
    controller: SocialNetworkController,
    ownMainDht: String,
    onOpenGroup: (String) -> Unit,
    onOpenPost: (String, String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var events by remember(ownMainDht) { mutableStateOf(emptyList<StoredGroupEventV2>()) }
    var profileComments by remember(ownMainDht) { mutableStateOf(emptyList<Comment>()) }
    var routeLines by remember(ownMainDht) { mutableStateOf(emptyList<String>()) }
    var view by remember { mutableStateOf("Events") }
    var filter by remember { mutableStateOf("All") }
    var direction by remember { mutableStateOf("Both") }
    var selectedId by remember(ownMainDht) { mutableStateOf<String?>(null) }
    var selectedProfileCommentId by remember(ownMainDht) { mutableStateOf<String?>(null) }
    var content by remember(selectedId) { mutableStateOf<WeaveMessage?>(null) }
    var contentStatus by remember(selectedId) { mutableStateOf("Loading content…") }
    val timeFormat = remember { DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()) }

    // Only this screen polls. Snapshot conversion occurs off the main thread and copies all mutable
    // evidence maps. A bounded 1.5s refresh keeps event traffic from driving UI recomposition.
    LaunchedEffect(ownMainDht) {
        while (true) {
            val snapshot = withContext(Dispatchers.IO) {
                val captured = controller.groupEventsV2.monitorSnapshot(250)
                val comments = controller.comments.monitorSnapshot(250)
                val lines = WeaveDiagnostics.tail(context, 180_000)
                    .lineSequence()
                    .filter { it.contains("[ROUTE]") || it.contains("[RPC]") }
                    .toList().takeLast(120)
                    .asReversed()
                Triple(captured, comments, lines)
            }
            events = snapshot.first
            profileComments = snapshot.second
            routeLines = snapshot.third
            delay(1_500)
        }
    }

    val selected = events.firstOrNull { it.event.eventId == selectedId }
    LaunchedEffect(selectedId, ownMainDht) {
        val record = events.firstOrNull { it.event.eventId == selectedId } ?: return@LaunchedEffect
        content = null
        if (record.validation != GroupEventValidationV2.Valid ||
            record.contentState != GroupEventContentStateV2.Valid) {
            contentStatus = "Content not verified or not yet available; open the group to refresh."
            return@LaunchedEffect
        }
        contentStatus = "Loading content…"
        controller.loadGroupMessage(record.event.payload.toWeaveRef()) { loaded ->
            if (selectedId == record.event.eventId) {
                content = loaded
                contentStatus = if (loaded == null) "Content not available on this device." else "Payload from a previously verified event"
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("←") }
                Text("Event monitor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = view == "Events", onClick = { view = "Events" }, label = { Text("Group events") })
            FilterChip(selected = view == "Routes", onClick = { view = "Routes" }, label = { Text("Transport calls") })
        }
        if (view == "Events") {
            Text(
                "Deduplicated by event ID. Last 250 retained group events plus 250 locally known profile comments; incoming/outgoing are this device's observations.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("All", "Posts", "Comments").forEach { option ->
                    FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option) })
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("Both", "Outgoing", "Incoming").forEach { option ->
                    FilterChip(selected = direction == option, onClick = { direction = option }, label = { Text(option) })
                }
            }
            val visibleGroups: Sequence<MonitorEntry> = events.asSequence().filter { record ->
                (filter == "All" || (filter == "Posts") == (record.event.eventType == GroupEventTypeV2.PostSubmitted)) &&
                (direction == "Both" || (direction == "Outgoing") == record.evidence.containsKey(GroupEventTransportV2.LocalCreated))
            }.map { MonitorEntry.Group(it) }
            val visibleComments: Sequence<MonitorEntry> = profileComments.asSequence().filter { comment ->
                filter != "Posts" &&
                (direction == "Both" || (direction == "Outgoing") == (comment.authorKey == ownMainDht))
            }.map { MonitorEntry.ProfileComment(it) }
            val visible = (visibleGroups + visibleComments).sortedByDescending { it.seenAt }.toList()
            Text(
                "${visible.size} events (${events.size} group · ${profileComments.size} profile comments)",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.key }) { entry ->
                    when (entry) {
                        is MonitorEntry.Group -> {
                            val record = entry.record
                            val event = record.event
                            val count = record.evidence.values.sumOf { it.count }
                            val outgoing = record.evidence.containsKey(GroupEventTransportV2.LocalCreated)
                            val groupName = controller.groups.byId(event.groupId)?.name ?: event.groupId.take(12)
                            Column(
                                Modifier.fillMaxWidth().clickable { selectedId = event.eventId }.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(if (event.eventType == GroupEventTypeV2.PostSubmitted) "Group post" else "Group comment",
                                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(if (outgoing) "↑ Outgoing" else "↓ Incoming", style = MaterialTheme.typography.labelSmall)
                                }
                                Text(groupName, style = MaterialTheme.typography.bodySmall)
                                Text("${event.eventId.take(8)} · ${timeFormat.format(Instant.ofEpochMilli(record.firstSeenAt))} · duplicates ${(count - 1).coerceAtLeast(0)}",
                                    style = MaterialTheme.typography.labelSmall)
                                Text(record.evidence.values.joinToString(" · ") { "${it.transport.name} ×${it.count}" },
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (record.conflicts.isNotEmpty()) {
                                    Text("⚠ ${record.conflicts.size} conflicting payload(s)", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        is MonitorEntry.ProfileComment -> {
                            val comment = entry.comment
                            Column(
                                Modifier.fillMaxWidth().clickable { selectedProfileCommentId = comment.id }.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Profile comment", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(if (comment.authorKey == ownMainDht) "↑ Outgoing" else "↓ Incoming", style = MaterialTheme.typography.labelSmall)
                                }
                                Text(comment.body.take(100), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                Text("${comment.id.take(8)} · ${timeFormat.format(Instant.ofEpochMilli(comment.createdAt))} · ${comment.state}",
                                    style = MaterialTheme.typography.labelSmall)
                                Text("${comment.origin.name} · deduplicated in comment store (sighting count unavailable)",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        } else {
            Text(
                "Recent Weave↔daemon calls and identified send paths. These are operations, not unique messages. Local DHT reads do not reveal Veilid's hop count; failed sends may have an unknown path.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(routeLines) { line ->
                    Text(line.take(340), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp))
                    HorizontalDivider()
                }
            }
        }
    }

    selected?.let { record ->
        val event = record.event
        val conversationId = if (event.eventType == GroupEventTypeV2.CommentSubmitted) {
            event.payload.parentObjectId
        } else "post:${event.payload.objectId}"
        val count = record.evidence.values.sumOf { it.count }
        AlertDialog(
            onDismissRequest = { selectedId = null },
            title = { Text(if (event.eventType == GroupEventTypeV2.PostSubmitted) "Group post" else "Group comment") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Event: ${event.eventId}", style = MaterialTheme.typography.labelSmall)
                    Text("Observations: $count · duplicates: ${(count - 1).coerceAtLeast(0)}")
                    Text("Signature: ${record.validation} · Content: ${record.contentState}")
                    Text("Author binding: ${record.authorBinding}")
                    Text("Source: ${record.evidence.values.joinToString { "${it.transport.name} ×${it.count}" }}")
                    if (record.branchStates.isNotEmpty()) {
                        Text("Branch states: ${record.branchStates.values.joinToString()}")
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(contentStatus, style = MaterialTheme.typography.labelSmall)
                    content?.let { message ->
                        message.title?.takeIf(String::isNotBlank)?.let {
                            Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        Text(message.body.take(3_000), style = MaterialTheme.typography.bodyMedium)
                        if (message.fullMedia.isNotEmpty()) {
                            Text("${message.fullMedia.size} media attachment(s) — open in group to view.")
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    selectedId = null
                    if (conversationId.isNotBlank()) onOpenPost(event.groupId, conversationId)
                    else onOpenGroup(event.groupId)
                }) { Text(if (conversationId.isNotBlank()) "Open post" else "Open group") }
            },
            dismissButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selectedId = null; onOpenProfile(event.authorMainDht) }) { Text("Author") }
                    TextButton(onClick = { selectedId = null }) { Text("Close") }
                }
            },
        )
    }
    profileComments.firstOrNull { it.id == selectedProfileCommentId }?.let { comment ->
        val profileKey = ownerKeyOf(comment.pageKey)
        AlertDialog(
            onDismissRequest = { selectedProfileCommentId = null },
            title = { Text("Profile comment") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("State: ${comment.state} · Transport: ${comment.origin}")
                    Text("Comment: ${comment.id}", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(comment.body)
                }
            },
            confirmButton = {
                Button(onClick = { selectedProfileCommentId = null; onOpenProfile(profileKey) }) {
                    Text("Open profile")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedProfileCommentId = null }) { Text("Close") }
            },
        )
    }

}

private sealed interface MonitorEntry {
    val key: String
    val seenAt: Long

    data class Group(val record: StoredGroupEventV2) : MonitorEntry {
        override val key = "group:${record.event.eventId}"
        override val seenAt = record.lastSeenAt
    }

    data class ProfileComment(val comment: Comment) : MonitorEntry {
        override val key = "profile:${comment.id}"
        override val seenAt = comment.createdAt
    }
}
