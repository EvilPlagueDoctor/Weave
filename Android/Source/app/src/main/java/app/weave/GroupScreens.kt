package app.weave

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun GroupHomeScreen(store: GroupStore, onOpen: (String) -> Unit) {
    val revision = store.revision
    val groups = remember(revision) { store.joined() }

    Column(Modifier.fillMaxSize()) {
        GroupModeHeader("Groups", "A quick view of the conversations moving across your groups.")
        if (groups.isEmpty()) {
            EmptyGroupState(
                title = "No groups yet",
                body = "Use Search to find groups, or Me to create one.",
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(groups, key = { it.groupId }) { group ->
                    GroupPulseCard(group, store.pulse(group.groupId), onClick = { onOpen(group.groupId) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun GroupSearchScreen(store: GroupStore, onOpen: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val revision = store.revision
    val results = remember(revision, query) { store.search(query) }

    Column(Modifier.fillMaxSize()) {
        GroupModeHeader("Search groups", "Group results use the group index/cache, never profile search results.")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(300) },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            label = { Text("Group name, topic or tag") },
            singleLine = true,
        )
        if (results.isEmpty()) {
            EmptyGroupState(
                title = if (query.isBlank()) "No known groups yet" else "No matching groups",
                body = if (query.isBlank())
                    "Groups you discover will appear here."
                else
                    "Try a broader name, topic or tag.",
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { it.groupId }) { group ->
                    GroupRow(group, onClick = { onOpen(group.groupId) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun GroupMeScreen(
    store: GroupStore,
    ownKey: String,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onManage: (String) -> Unit,
    onModeration: () -> Unit,
) {
    val revision = store.revision
    val owned = remember(revision, ownKey) { store.owned(ownKey) }
    val joined = remember(revision) {
        store.joined().filter { group -> owned.none { it.groupId == group.groupId } }
    }
    val pending = remember(revision) { store.pendingActionCount() }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            GroupModeHeader("My groups", "Create, manage and moderate groups from here.")
            if (pending > 0) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable(onClick = onModeration)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Needs attention", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("$pending", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Button(onClick = onCreate, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("+ Create group")
            }
            SectionLabel("Your groups")
        }

        if (owned.isEmpty()) {
            item { HintText("You haven't created or moderated any groups yet.") }
        } else {
            items(owned, key = { "owned:${it.groupId}" }) { group ->
                val canEditMetadata = group.role == GroupRole.Creator || group.ownerId == ownKey
                GroupManagementRow(
                    group,
                    onOpen = { onOpen(group.groupId) },
                    onManage = if (canEditMetadata) ({ onManage(group.groupId) }) else null,
                )
            }
        }

        item { SectionLabel("Joined groups") }
        if (joined.isEmpty()) {
            item { HintText("Groups you join will appear here.") }
        } else {
            items(joined, key = { "joined:${it.groupId}" }) { group ->
                GroupRow(group, onClick = { onOpen(group.groupId) })
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun GroupDetailScreen(
    store: GroupStore,
    groupId: String,
    ownMainDht: String,
    onBack: () -> Unit,
    onManage: (() -> Unit)? = null,
    onRefreshBranch: ((GroupBranchPointer) -> Unit)? = null,
    onClaim: (() -> Unit)? = null,
    onJoin: (() -> Unit)? = null,
    onLeave: (() -> Unit)? = null,
    onAddModerator: ((String) -> Unit)? = null,
) {
    val revision = store.revision
    val group = remember(revision, groupId) { store.byId(groupId) }
    val branchList = remember(revision, groupId) { store.branchesFor(groupId) }
    val selected = remember(revision, groupId) { store.selectedBranch(groupId) }
    val pulse = remember(revision, groupId, selected?.branchId) { store.pulse(groupId) }
    val pendingItems = remember(revision, groupId) { store.pendingFor(groupId) }
    val pendingPublic = pendingItems.count { it.publicSpectator }
    val pendingEncrypted = pendingItems.count { it.encryptedSpectator }
    var branchMenu by remember { mutableStateOf(false) }
    var showModeratorDialog by remember { mutableStateOf(false) }
    var moderatorKey by remember { mutableStateOf("") }

    LaunchedEffect(selected?.branchRoot, group?.rootRecordKey) {
        val branch = selected ?: group?.rootRecordKey?.takeIf { it.isNotBlank() }?.let {
            GroupBranchPointer(
                groupId = groupId,
                branchId = GroupBranchNetwork.originalBranchId(groupId),
                branchRoot = it,
                ownerMainDht = group.ownerId,
                kind = GroupBranchKind.Original,
                creatorRoot = it,
                updatedAt = group.updatedAt,
            )
        }
        branch?.let { onRefreshBranch?.invoke(it) }
    }

    if (group == null) {
        EmptyGroupState("Group unavailable", "This cached group entry no longer exists.")
        return
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("\u2190") }
                Column(Modifier.weight(1f)) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${group.approximateMembers} members \u00B7 ${group.policy.visibility.pretty()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                onManage?.let { TextButton(onClick = it) { Text("Manage") } }
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (selected?.kind == GroupBranchKind.Claim) "Moderation: Claim" else "Moderation: Original",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (branchList.size > 1) {
                            Box {
                                TextButton(onClick = { branchMenu = true }) { Text("Change") }
                                DropdownMenu(expanded = branchMenu, onDismissRequest = { branchMenu = false }) {
                                    branchList.forEach { branch ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(if (branch.kind == GroupBranchKind.Original) "Original" else "Claim")
                                                    Text(
                                                        shortBranchOwner(branch.ownerMainDht),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            },
                                            onClick = {
                                                store.selectBranch(groupId, branch.branchId)
                                                branchMenu = false
                                                onRefreshBranch?.invoke(branch)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (selected?.kind == GroupBranchKind.Claim) {
                        Text(
                            "This is an alternate moderation of the same group, not a fork.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val canClaim = group.policy.visibility != GroupVisibility.MembersOnly &&
                        group.ownerId != ownMainDht &&
                        store.ownClaim(groupId, ownMainDht) == null
                    if (canClaim && onClaim != null) {
                        OutlinedButton(onClick = onClaim, modifier = Modifier.padding(top = 8.dp)) {
                            Text("Claim moderation")
                        }
                    }

                    if (group.ownerId != ownMainDht) {
                        if (group.joined) {
                            TextButton(onClick = { onLeave?.invoke() }, modifier = Modifier.padding(top = 4.dp)) {
                                Text("Leave group")
                            }
                        } else {
                            when (group.policy.join) {
                                GroupJoinPolicy.Invite -> Text(
                                    "Invite only",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                GroupJoinPolicy.Open -> Button(
                                    onClick = { onJoin?.invoke() },
                                    modifier = Modifier.padding(top = 8.dp),
                                ) { Text("Join group") }
                                GroupJoinPolicy.Request -> OutlinedButton(
                                    onClick = { onJoin?.invoke() },
                                    modifier = Modifier.padding(top = 8.dp),
                                ) { Text("Ask to join") }
                            }
                        }
                    }

                    val ownsSelectedBranch = selected?.ownerMainDht == ownMainDht ||
                        (selected == null && group.ownerId == ownMainDht)
                    if (ownsSelectedBranch && onAddModerator != null) {
                        TextButton(
                            onClick = { showModeratorDialog = true },
                            modifier = Modifier.padding(top = 4.dp),
                        ) { Text("Add branch moderator") }
                    }
                }
                HorizontalDivider()
            }

            if (group.description.isNotBlank()) {
                item { Text(group.description, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
            }
            if (group.featured.kind != FeaturedKind.None) item { FeaturedCard(group.featured) }

            if (pendingPublic + pendingEncrypted > 0) {
                item {
                    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            val count = pendingPublic + pendingEncrypted
                            Text("$count unreviewed item${if (count == 1) "" else "s"}",
                                fontWeight = FontWeight.SemiBold)
                            Text(
                                if (pendingEncrypted > 0 && group.policy.visibility == GroupVisibility.MembersOnly)
                                    "These came from the spectator-readable intake mailbox encrypted for group members. They are still unreviewed."
                                else
                                    "These came from the open spectator-readable intake mailbox. They are not approvals by this moderation branch.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            item { SectionLabel("Group pulse") }
            if (pulse.conversations.isEmpty()) {
                item { HintText("No conversations have been active recently.") }
            } else {
                items(pulse.conversations, key = { it.conversation.objectId }) { conversation ->
                    ConversationPulseCard(conversation)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showModeratorDialog) {
        AlertDialog(
            onDismissRequest = { showModeratorDialog = false },
            title = { Text("Add branch moderator") },
            text = {
                OutlinedTextField(
                    value = moderatorKey,
                    onValueChange = { moderatorKey = it.trim().take(512) },
                    label = { Text("Moderator main DHT") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (moderatorKey.isNotBlank()) onAddModerator?.invoke(moderatorKey)
                        moderatorKey = ""
                        showModeratorDialog = false
                    },
                    enabled = moderatorKey.isNotBlank(),
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showModeratorDialog = false }) { Text("Cancel") }
            },
        )
    }
}

private fun shortBranchOwner(value: String): String =
    if (value.length <= 18) value else "${value.take(8)}…${value.takeLast(6)}"

@Composable
fun GroupEditorScreen(
    store: GroupStore,
    ownKey: String,
    groupId: String?,
    onPublish: (GroupRecord, GroupPulse) -> Unit,
    onSaved: (String) -> Unit,
    onBack: () -> Unit,
) {
    val original = remember(store.revision, groupId) { groupId?.let(store::byId) }
    var name by remember(original?.groupId) { mutableStateOf(original?.name.orEmpty()) }
    var description by remember(original?.groupId) { mutableStateOf(original?.description.orEmpty()) }
    var tags by remember(original?.groupId) { mutableStateOf(original?.tags?.joinToString(", ").orEmpty()) }
    var join by remember(original?.groupId) { mutableStateOf(original?.policy?.join ?: GroupJoinPolicy.Open) }
    var posting by remember(original?.groupId) { mutableStateOf(original?.policy?.posting ?: GroupPostingPolicy.Everyone) }
    var moderation by remember(original?.groupId) { mutableStateOf(original?.policy?.moderation ?: GroupModerationPreset.Balanced) }
    var visibility by remember(original?.groupId) { mutableStateOf(original?.policy?.visibility ?: GroupVisibility.Public) }
    var featuredKind by remember(original?.groupId) { mutableStateOf(original?.featured?.kind ?: FeaturedKind.None) }
    var featuredTitle by remember(original?.groupId) { mutableStateOf(original?.featured?.title.orEmpty()) }
    var featuredBody by remember(original?.groupId) { mutableStateOf(original?.featured?.body.orEmpty()) }
    var showAdvanced by remember { mutableStateOf(false) }
    var quarantineNew by remember(original?.groupId) { mutableStateOf(original?.policy?.quarantineNewMembers ?: false) }
    var duplicates by remember(original?.groupId) { mutableStateOf(original?.policy?.duplicateProtection ?: true) }
    var rateProtection by remember(original?.groupId) { mutableStateOf(original?.policy?.excessivePostingProtection ?: true) }
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) { Text("\u2190") }
                Text(
                    if (original == null) "Create group" else "Manage group",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("Name") })
            OutlinedTextField(
                description, { description = it.take(2000) }, Modifier.fillMaxWidth(),
                label = { Text("Description") }, minLines = 3
            )
            OutlinedTextField(tags, { tags = it.take(500) }, Modifier.fillMaxWidth(), label = { Text("Topics / tags") })

            Text("Simple controls", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            EnumPicker("Joining", join, GroupJoinPolicy.entries) { join = it }
            EnumPicker("Posting", posting, GroupPostingPolicy.entries) { posting = it }
            EnumPicker("Moderation", moderation, GroupModerationPreset.entries) { moderation = it }
            EnumPicker("Visibility", visibility, GroupVisibility.entries) { visibility = it }

            HorizontalDivider()
            Text("Featured area", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            EnumPicker("Type", featuredKind, FeaturedKind.entries) { featuredKind = it }
            if (featuredKind != FeaturedKind.None) {
                OutlinedTextField(
                    featuredTitle, { featuredTitle = it.take(120) }, Modifier.fillMaxWidth(),
                    label = { Text("Featured title") }
                )
                OutlinedTextField(
                    featuredBody, { featuredBody = it.take(1200) }, Modifier.fillMaxWidth(),
                    label = { Text("Featured text / note") }, minLines = 2
                )
                Text(
                    if (featuredKind == FeaturedKind.Post || featuredKind == FeaturedKind.Widget)
                        "A post/widget ObjectRef can be selected from the group after it has been published."
                    else
                        "This stays deliberately short so the actual conversations remain above the fold.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "Hide advanced" else "Advanced")
            }
            if (showAdvanced) {
                ToggleRow("Quarantine new members", quarantineNew) { quarantineNew = it }
                ToggleRow("Repeated-message protection", duplicates) { duplicates = it }
                ToggleRow("Excessive-posting protection", rateProtection) { rateProtection = it }
            }

            Button(
                onClick = {
                    val policy = GroupPolicy(
                        join = join,
                        posting = posting,
                        moderation = moderation,
                        visibility = visibility,
                        quarantineNewMembers = quarantineNew,
                        duplicateProtection = duplicates,
                        excessivePostingProtection = rateProtection,
                    )
                    val featured = FeaturedSlot(
                        kind = featuredKind,
                        title = featuredTitle,
                        body = featuredBody,
                        ref = original?.featured?.ref,
                    )
                    val parsedTags = tags.split(',', ';', '\n')
                    val saved = if (original == null) {
                        store.createGroup(ownKey, name, description, parsedTags, policy, featured)
                    } else {
                        original.copy(
                            name = name.trim().ifBlank { original.name },
                            description = description.trim(),
                            tags = parsedTags.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(32),
                            policy = policy,
                            featured = featured,
                        ).also(store::updateGroup)
                    }
                    onPublish(saved, store.pulse(saved.groupId))
                    onSaved(saved.groupId)
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (original == null) "Create group" else "Save changes")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun GroupModerationScreen(
    store: GroupStore,
    onBack: () -> Unit,
    onResolve: (taskId: String, approve: Boolean) -> Unit = { _, _ -> },
) {
    val revision = store.revision
    val tasks = remember(revision) { store.pendingTasks() }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("\u2190") }
                Text("Group moderation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
        if (tasks.isEmpty()) {
            EmptyGroupState("Nothing needs attention", "Reports, join requests and quarantined posts will appear here.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(tasks, key = { it.taskId }) { task ->
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(task.kind.pretty(), fontWeight = FontWeight.SemiBold)
                        if (task.reason.isNotBlank()) Text(task.reason, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { onResolve(task.taskId, false) }) {
                                Text("Reject / Drop")
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { onResolve(task.taskId, true) }) {
                                Text("Approve / Keep")
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun GroupModeHeader(title: String, subtitle: String) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun GroupPulseCard(group: GroupRecord, pulse: GroupPulse, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(group.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (pulse.conversations.isNotEmpty()) {
                Text(
                    "${pulse.conversations.sumOf { it.recentUniqueAuthors }} active",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        pulse.conversations.take(2).forEach { conversation ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(conversation.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    conversation.recentMessages.take(2).forEach { MessagePreviewLine(it) }
                }
            }
        }
        if (pulse.conversations.isEmpty()) {
            Text(
                group.description.ifBlank { "No cached conversation activity yet." },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConversationPulseCard(conversation: GroupConversationPreview) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(conversation.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (conversation.pinned) Text("\uD83D\uDCCC")
                if (conversation.locked) Text("\uD83D\uDD12", modifier = Modifier.padding(start = 6.dp))
            }
            conversation.recentMessages.take(3).forEach { MessagePreviewLine(it) }
            Text(
                "${conversation.recentUniqueAuthors} recent participants",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun MessagePreviewLine(message: MessagePreview) {
    Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.Top) {
        TinyThumbnail(message.thumbnailBase64)
        Column(Modifier.weight(1f)) {
            Text(message.authorName, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            Text(
                message.bodyPreview,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TinyThumbnail(encoded: String?) {
    val bitmap = remember(encoded) {
        encoded?.let {
            runCatching {
                val bytes = Base64.decode(it, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Message thumbnail",
            modifier = Modifier.size(52.dp).padding(end = 8.dp),
        )
    }
}

@Composable
private fun FeaturedCard(slot: FeaturedSlot) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                when (slot.kind) {
                    FeaturedKind.Post -> "\uD83D\uDCCC Featured post"
                    FeaturedKind.Widget -> "Featured widget"
                    FeaturedKind.Message -> "Featured"
                    FeaturedKind.None -> ""
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            if (slot.title.isNotBlank()) {
                Text(slot.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            }
            if (slot.body.isNotBlank()) {
                Text(slot.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            }
            if (slot.ref != null) {
                Text(
                    "Linked ${slot.ref.type.name.lowercase()} \u00B7 tap-through can resolve ${slot.ref.objectId.take(12)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun GroupRow(group: GroupRecord, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(group.name, fontWeight = FontWeight.SemiBold)
            if (group.description.isNotBlank()) {
                Text(
                    group.description,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text("${group.approximateMembers}", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun GroupManagementRow(group: GroupRecord, onOpen: () -> Unit, onManage: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(onClick = onOpen).padding(vertical = 8.dp)) {
            Text(group.name, fontWeight = FontWeight.SemiBold)
            Text(group.role.pretty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onManage != null) {
            TextButton(onClick = onManage) { Text("Manage") }
        } else {
            TextButton(onClick = onOpen) { Text("Open") }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun EmptyGroupState(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun <T : Enum<T>> EnumPicker(label: String, value: T, values: List<T>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text(value.prettyName()) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                values.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.prettyName()) },
                        onClick = { onSelect(option); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun Enum<*>.prettyName(): String =
    name.replace(Regex("([a-z])([A-Z])"), "$1 $2")

private fun GroupVisibility.pretty() = prettyName()
private fun GroupRole.pretty() = prettyName()
private fun GroupModerationKind.pretty() = prettyName()
