package app.weave

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val GROUP_OPEN_REFRESH_MS = 10_000L

data class LocalPendingPostUi(
    val title: String,
    val body: String,
    val authorName: String,
    val conversationId: String? = null,
    val status: String = "Uploading…",
    val failed: Boolean = false,
)

/**
 * Hoisted out of GroupDetailScreen so an in-flight post does not visually disappear when the
 * person visits another section. The controller owns the real upload job; this object only keeps
 * the user-visible status attached to that group while Weave remains open.
 */
@Stable
class GroupPostSubmissionUiState {
    var uploading by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
    var failed by mutableStateOf(false)
    var submittedConversationId by mutableStateOf<String?>(null)
    var optimisticPost by mutableStateOf<LocalPendingPostUi?>(null)
    var draftTitle by mutableStateOf("")
    var draftBody by mutableStateOf("")
    var draftImage by mutableStateOf<LocalMediaStore.Stored?>(null)
    var draftAudio by mutableStateOf<LocalMediaStore.AudioStored?>(null)
}

@Composable
fun GroupHomeScreen(store: GroupStore, onOpen: (String) -> Unit) {
    val revision = store.revision
    val groups = remember(revision) { store.joined() }

    Column(Modifier.fillMaxSize()) {
        GroupModeHeader("Snapshot", "A quick view of the conversations moving across your groups.")
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
fun GroupSearchScreen(
    store: GroupStore,
    onOpen: (String) -> Unit,
    onResolveLink: (String) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var lastResolvedLink by remember { mutableStateOf("") }
    val revision = store.revision
    val parsedLink = remember(query) { GroupLink.parse(query) }

    LaunchedEffect(query) {
        if (parsedLink == null) {
            lastResolvedLink = ""
        } else if (query != lastResolvedLink) {
            lastResolvedLink = query
            onResolveLink(query)
        }
    }

    val results = remember(revision, query, parsedLink) {
        if (parsedLink != null) {
            store.byId(parsedLink.groupId)?.let(::listOf) ?: emptyList()
        } else {
            store.search(query)
        }
    }

    Column(Modifier.fillMaxSize()) {
        GroupModeHeader("Search", "Groups learned from people appear here. You can also paste a Weave group link.")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(1200) },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            label = { Text("Group name, topic, tag or Weave link") },
            singleLine = true,
        )
        if (results.isEmpty()) {
            EmptyGroupState(
                title = if (parsedLink != null) "Resolving group link…" else if (query.isBlank()) "No known groups yet" else "No matching groups",
                body = if (parsedLink != null)
                    "Weave is checking the creator's group DHT."
                else if (query.isBlank())
                    "Groups discovered through people will appear here."
                else
                    "Try a broader name/topic or paste a group link.",
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
    onSettings: () -> Unit,
) {
    val revision = store.revision
    val owned = remember(revision, ownKey) { store.owned(ownKey) }
    val joined = remember(revision) {
        store.joined().filter { group -> owned.none { it.groupId == group.groupId } }
    }
    val pending = remember(revision) { store.pendingActionCount() }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .clickable(onClick = onSettings)
                            .padding(end = 12.dp)
                    ) {
                        Identicon(ownKey, size = 42.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Curator", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Create, manage and moderate groups from here.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
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
    media: LocalMediaStore,
    groupId: String,
    ownMainDht: String,
    ownDisplayName: String = "You",
    submissionUi: GroupPostSubmissionUiState,
    authorityContinuity: GroupAuthorityContinuityV2? = null,
    onBack: () -> Unit,
    onOpenPost: (String) -> Unit,
    onRefreshBranch: ((GroupBranchPointer) -> Unit)? = null,
    onJoin: (() -> Unit)? = null,
    onLeave: (() -> Unit)? = null,
    onClaimModeration: (((Boolean, String) -> Unit) -> Unit)? = null,
    branchOwnerName: (String) -> String? = { null },
    onSelectBranch: ((GroupBranchPointer) -> Unit)? = null,
    onCreatePost: ((String, String, LocalMediaStore.Stored?, LocalMediaStore.AudioStored?, (Boolean, String?) -> Unit) -> Unit)? = null,
    onSetFeatured: ((FeaturedSlot) -> Unit)? = null,
    onAddModerator: ((GroupModeratorGrant) -> Unit)? = null,
    onLoadCuratorPosts: (((List<GroupCuratorPost>) -> Unit) -> Unit)? = null,
    onLoadMessage: ((WeaveObjectRef, (WeaveMessage?) -> Unit) -> Unit)? = null,
    onDeletePost: ((String, WeaveObjectRef, String, (Boolean, String) -> Unit) -> Unit)? = null,
    onModifyPost: ((String, WeaveObjectRef, String, String, (Boolean, String) -> Unit) -> Unit)? = null,
    onBanAuthor: ((String, (Boolean, String) -> Unit) -> Unit)? = null,
) {
    val context = LocalContext.current
    val revision = store.revision
    val group = remember(revision, groupId) { store.byId(groupId) }
    val branchList = remember(revision, groupId) { store.branchesFor(groupId) }
    val selected = remember(revision, groupId) { store.selectedBranch(groupId) }
    val selectedHeader = remember(revision, groupId, selected?.branchId) { store.selectedHeader(groupId) }
    val pulse = remember(revision, groupId, selected?.branchId) { store.pulse(groupId) }
    val pendingItems = remember(revision, groupId) { store.pendingFor(groupId) }
    val pendingPublic = pendingItems.count { it.publicSpectator }
    val pendingEncrypted = pendingItems.count { it.encryptedSpectator }

    // A group's branch root is long-lived while its Pulse (subkey 1) changes as new posts and
    // comments are accepted. Force an immediate refresh whenever this screen/branch becomes active,
    // then poll only while the group is actually on screen. Leaving the composable cancels this
    // LaunchedEffect automatically, so known groups are not continuously polled in the background.
    val currentRefreshBranch by rememberUpdatedState(onRefreshBranch)
    LaunchedEffect(groupId, selected?.branchId, selected?.branchRoot) {
        val branch = selected ?: return@LaunchedEffect
        val refresh = currentRefreshBranch ?: return@LaunchedEffect
        refresh(branch)
        while (true) {
            delay(GROUP_OPEN_REFRESH_MS)
            currentRefreshBranch?.invoke(branch)
        }
    }

    val ownGrant = selectedHeader?.moderators?.firstOrNull { it.moderatorMainDht == ownMainDht }
    val ownsBranch = selected?.ownerMainDht == ownMainDht ||
        (selected == null && group?.ownerId == ownMainDht)
    val canEditFeatured = ownsBranch || ownGrant?.canEditFeatured == true
    val canModeratePosts = ownsBranch || ownGrant?.canModeratePosts == true

    var branchMenu by remember { mutableStateOf(false) }
    var descriptionExpanded by remember { mutableStateOf(false) }
    var showCreatePost by remember { mutableStateOf(false) }
    var postTitle by remember(groupId) { mutableStateOf(submissionUi.draftTitle) }
    var postBody by remember(groupId) { mutableStateOf(submissionUi.draftBody) }
    var postImage by remember(groupId) { mutableStateOf(submissionUi.draftImage) }
    var importingPostImage by remember { mutableStateOf(false) }
    var postAudio by remember(groupId) { mutableStateOf(submissionUi.draftAudio) }
    var importingPostAudio by remember { mutableStateOf(false) }
    var curatorView by remember(groupId) { mutableStateOf(false) }
    var curatorLoading by remember(groupId) { mutableStateOf(false) }
    var curatorPosts by remember(groupId) { mutableStateOf<List<GroupCuratorPost>>(emptyList()) }
    var deleteTarget by remember(groupId) { mutableStateOf<GroupConversationPreview?>(null) }
    var deleteReason by remember(groupId) { mutableStateOf("") }
    var modifyTarget by remember(groupId) { mutableStateOf<GroupConversationPreview?>(null) }
    var modifyTitle by remember(groupId) { mutableStateOf("") }
    var modifyBody by remember(groupId) { mutableStateOf("") }
    var moderationBusy by remember(groupId) { mutableStateOf(false) }
    var banTarget by remember(groupId) { mutableStateOf<MessagePreview?>(null) }
    var showModeratorDialog by remember { mutableStateOf(false) }
    var moderatorKey by remember { mutableStateOf("") }
    var modPosts by remember { mutableStateOf(true) }
    var modMembers by remember { mutableStateOf(true) }
    var modReports by remember { mutableStateOf(true) }
    var modPin by remember { mutableStateOf(true) }
    var modFeatured by remember { mutableStateOf(true) }
    var showFeaturedDialog by remember { mutableStateOf(false) }
    var showClaimConfirm by remember { mutableStateOf(false) }
    var claimingModeration by remember { mutableStateOf(false) }
    var claimNotice by remember { mutableStateOf<String?>(null) }
    var claimFailed by remember { mutableStateOf(false) }
    var customFeaturedTitle by remember { mutableStateOf("") }
    var customFeaturedBody by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val postImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importingPostImage = true
        scope.launch {
            postImage = media.importImage(uri)
            importingPostImage = false
        }
    }
    val postAudioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importingPostAudio = true
        scope.launch {
            postAudio = media.importAudio(uri)
            importingPostAudio = false
        }
    }

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

    val originalFallback = remember(group.groupId, group.rootRecordKey, group.ownerId, group.updatedAt) {
        group.rootRecordKey.takeIf { it.isNotBlank() }?.let {
            GroupBranchPointer(
                groupId = group.groupId,
                branchId = GroupBranchNetwork.originalBranchId(group.groupId),
                branchRoot = it,
                ownerMainDht = group.ownerId,
                kind = GroupBranchKind.Original,
                creatorRoot = it,
                updatedAt = group.updatedAt,
            )
        }
    }
    val menuBranches = remember(branchList, selected, originalFallback) {
        buildList {
            originalFallback?.let(::add)
            selected?.let(::add)
            addAll(branchList)
        }.distinctBy { it.branchId }
            .sortedWith(compareBy<GroupBranchPointer> { it.kind != GroupBranchKind.Original }.thenByDescending { it.updatedAt })
    }
    val moderationKindLabel = if (selected?.kind == GroupBranchKind.Claim) "Claim" else "Original"
    val selectedOwnerName = selected?.ownerMainDht?.let(branchOwnerName)?.takeIf { it.isNotBlank() }
    val moderationLabel = if (curatorView) "Curator" else
        selectedOwnerName?.let { "$moderationKindLabel · $it" } ?: moderationKindLabel
    val groupLink = remember(group.groupId, group.rootRecordKey) { GroupLink.forGroup(group)?.encode() }
    val canPost = group.joined || group.ownerId == ownMainDht
    val alreadyOwnsClaim = branchList.any { it.ownerMainDht == ownMainDht }
    val canClaimModeration = group.policy.visibility != GroupVisibility.MembersOnly &&
        group.ownerId != ownMainDht && !alreadyOwnsClaim && onClaimModeration != null
    val posts = pulse.conversations
        .filter { it.conversation.objectId.startsWith("post:") && it.rootMessage != null }
        .sortedByDescending { it.lastActivity }

    LaunchedEffect(curatorView, groupId) {
        if (!curatorView) return@LaunchedEffect
        val loader = onLoadCuratorPosts ?: return@LaunchedEffect
        curatorLoading = true
        loader { loaded ->
            curatorPosts = loaded
            curatorLoading = false
        }
    }

    LaunchedEffect(revision, submissionUi.submittedConversationId) {
        val submitted = submissionUi.submittedConversationId ?: return@LaunchedEffect
        if (posts.any { it.conversation.objectId == submitted }) {
            submissionUi.optimisticPost = null
            submissionUi.notice = "Post is now visible on this moderation branch."
            submissionUi.failed = false
            delay(2500)
            if (submissionUi.submittedConversationId == submitted) {
                submissionUi.notice = null
                submissionUi.submittedConversationId = null
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("\u2190") }
                GroupThumbnail(group.thumbnailBase64, group.groupId, group.name, size = 42.dp)
                Spacer(Modifier.width(8.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        group.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${group.approximateMembers} member${if (group.approximateMembers == 1) "" else "s"} \u00B7 ${group.policy.visibility.pretty()} \u00B7",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(4.dp))
                        Box(Modifier.widthIn(max = 112.dp)) {
                            Text(
                                moderationLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { branchMenu = true },
                            )
                            DropdownMenu(expanded = branchMenu, onDismissRequest = { branchMenu = false }) {
                                if (menuBranches.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Original") },
                                        enabled = false,
                                        onClick = {},
                                    )
                                }
                                menuBranches.forEach { branch ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(if (branch.kind == GroupBranchKind.Original) "Original" else "Claim")
                                                Text(
                                                    branchOwnerName(branch.ownerMainDht)?.takeIf { it.isNotBlank() }
                                                        ?: shortBranchOwner(branch.ownerMainDht),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        onClick = {
                                            curatorView = false
                                            store.selectBranch(groupId, branch.branchId)
                                            branchMenu = false
                                            onSelectBranch?.invoke(branch)
                                            onRefreshBranch?.invoke(branch)
                                        },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("Curator")
                                            Text(
                                                "Retained and pending submissions",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    onClick = {
                                        curatorView = true
                                        branchMenu = false
                                    },
                                )
                            }
                        }
                    }
                }

                if (groupLink != null) {
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Weave group", groupLink))
                        }
                    ) { Text("\uD83D\uDD17") }
                }

                if (group.ownerId != ownMainDht) {
                    Column(horizontalAlignment = Alignment.End) {
                        if (group.joined) {
                            TextButton(onClick = { onLeave?.invoke() }) {
                                Text("Leave group", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            when (group.policy.join) {
                                GroupJoinPolicy.Invite -> Text(
                                    "Invite only",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                                GroupJoinPolicy.Open -> Button(onClick = { onJoin?.invoke() }) {
                                    Text("Join", maxLines = 1, softWrap = false)
                                }
                                GroupJoinPolicy.Request -> OutlinedButton(onClick = { onJoin?.invoke() }) {
                                    Text("Ask to join", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            if (group.description.isNotBlank()) {
                item {
                    Text(
                        group.description,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { descriptionExpanded = !descriptionExpanded }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (descriptionExpanded) Int.MAX_VALUE else 1,
                        overflow = if (descriptionExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                    )
                }
            }

            when (authorityContinuity?.level) {
                GroupAuthorityContinuityLevelV2.QuietConcern -> item {
                    Text(
                        authorityContinuity.message,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                GroupAuthorityContinuityLevelV2.ClaimRecommended -> item {
                    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Moderation continuity", fontWeight = FontWeight.SemiBold)
                            Text(
                                authorityContinuity.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (canClaimModeration) {
                                Button(
                                    onClick = { showClaimConfirm = true },
                                    enabled = !claimingModeration,
                                ) { Text("Create independent Claim") }
                            }
                        }
                    }
                }
                else -> Unit
            }

            claimNotice?.let { notice ->
                item {
                    Text(
                        notice,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (claimFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (group.featured.kind != FeaturedKind.None || canEditFeatured) {
                item {
                    FeaturedCard(
                        slot = group.featured,
                        canEdit = canEditFeatured,
                        onEdit = { showFeaturedDialog = true },
                    )
                }
            }

            if (submissionUi.uploading || submissionUi.notice != null) {
                item {
                    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (submissionUi.uploading) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (submissionUi.uploading) "Uploading your post…" else submissionUi.notice.orEmpty(),
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (submissionUi.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                )
                                if (submissionUi.uploading) {
                                    Text(
                                        "Media is uploaded first, then the post is delivered to the group's moderation branches.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else if (!submissionUi.failed && submissionUi.submittedConversationId != null) {
                                    Text(
                                        "It may remain unreviewed until the selected branch accepts it.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (!submissionUi.uploading) {
                                TextButton(onClick = { submissionUi.notice = null; submissionUi.failed = false }) { Text("Dismiss") }
                            }
                        }
                    }
                }
            }

            if (pendingPublic + pendingEncrypted > 0) {
                item {
                    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            val count = pendingPublic + pendingEncrypted
                            Text("$count unreviewed item${if (count == 1) "" else "s"}", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Temporary intake activity is shown separately until the selected moderation branch accepts or removes it.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (curatorView) "Curator-held posts" else "Posts",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (!curatorView && ownsBranch && onAddModerator != null) {
                        TextButton(onClick = { showModeratorDialog = true }) { Text("Moderators", maxLines = 1, softWrap = false) }
                    }
                    if (!curatorView && canPost && onCreatePost != null) {
                        Button(
                            onClick = { showCreatePost = true },
                            enabled = !submissionUi.uploading,
                        ) {
                            Text("Create a post", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            if (curatorView) {
                item {
                    Text(
                        "This view shows post submissions still retained by this device's curator/custody layer, including items that a moderation branch has not exposed.",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    curatorLoading -> item {
                        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                    curatorPosts.isEmpty() -> item { HintText("No retained post submissions are available on this device.") }
                    else -> items(curatorPosts, key = { "curator:${it.eventId}" }) { item ->
                        CuratorPostCard(item)
                    }
                }
            } else {
                submissionUi.optimisticPost?.let { pending ->
                    item(key = "local-pending-post") { PendingLocalPostCard(pending) }
                }
                if (posts.isEmpty() && submissionUi.optimisticPost == null) {
                    item { HintText("No posts yet.") }
                } else {
                    items(posts, key = { it.conversation.objectId }) { post ->
                        GroupPostCard(
                            post = post,
                            canModerate = canModeratePosts,
                            onClick = { onOpenPost(post.conversation.objectId) },
                            onBan = { banTarget = post.rootMessage },
                            onDelete = {
                                deleteTarget = post
                                deleteReason = ""
                            },
                            onModify = {
                                val ref = post.rootMessage?.fullMessage
                                if (ref != null) {
                                    moderationBusy = true
                                    onLoadMessage?.invoke(ref) { message ->
                                        moderationBusy = false
                                        if (message != null) {
                                            modifyTarget = post
                                            modifyTitle = message.title ?: post.title
                                            modifyBody = message.body
                                        } else {
                                            android.widget.Toast.makeText(context, "Couldn't load the source post.", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                        )
                    }
                }

                if (pulse.removedPosts.isNotEmpty()) {
                    item { SectionLabel("Removed on this branch") }
                    items(pulse.removedPosts, key = { "removed:${it.postId}" }) { removed ->
                        RemovedPostCard(removed)
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showClaimConfirm) {
        AlertDialog(
            onDismissRequest = { if (!claimingModeration) showClaimConfirm = false },
            title = { Text("Claim moderation of this group?") },
            text = {
                Text(
                    "This creates your own independent moderation branch. It does not replace, revoke, or take control of the Original branch. People can choose which moderation branch they want to view."
                )
            },
            confirmButton = {
                Button(
                    enabled = !claimingModeration,
                    onClick = {
                        val claim = onClaimModeration ?: return@Button
                        claimingModeration = true
                        claim { ok, message ->
                            claimingModeration = false
                            showClaimConfirm = false
                            claimFailed = !ok
                            claimNotice = message
                        }
                    },
                ) { Text(if (claimingModeration) "Creating…" else "Create Claim") }
            },
            dismissButton = {
                TextButton(
                    enabled = !claimingModeration,
                    onClick = { showClaimConfirm = false },
                ) { Text("Cancel") }
            },
        )
    }

    if (showCreatePost) {
        AlertDialog(
            onDismissRequest = { showCreatePost = false },
            title = { Text("Create a post") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = postTitle,
                        onValueChange = { postTitle = it.take(160) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Title") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = postBody,
                        onValueChange = { postBody = it.take(MAX_COMMENT_CHARS) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Post") },
                        minLines = 4,
                        maxLines = 10,
                    )
                    postImage?.let { selected ->
                        media.bitmapFor(selected.contentHash)?.let { bitmap ->
                            FilteredImage(
                                contentId = "group-compose-image:${selected.contentHash}",
                                bitmap = bitmap,
                                modifier = Modifier.fillMaxWidth(),
                            ) { gateModifier ->
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = "Selected post image",
                                    modifier = gateModifier.fillMaxWidth().heightIn(max = 180.dp),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }
                    }
                    Text(
                        if (postImage == null)
                            "Add an optional picture. A thumbnail is stored in the post preview; the full image stays remote until someone taps it."
                        else
                            "This post will include an image. The thumbnail is embedded; the full image loads only when opened.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = {
                                postImagePicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            enabled = !importingPostImage,
                        ) {
                            if (importingPostImage) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text(if (postImage == null) "Add picture" else "Change picture")
                            }
                        }
                        if (postImage != null) {
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { postImage = null }) { Text("Remove") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { postAudioPicker.launch("audio/*") },
                            enabled = !importingPostAudio,
                        ) {
                            if (importingPostAudio) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text(if (postAudio == null) "Add audio" else "Change audio")
                            }
                        }
                        postAudio?.let { audio ->
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (audio.durationMs > 0) "${audio.durationMs / 1000}s" else "Audio",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            TextButton(onClick = { postAudio = null }) { Text("Remove") }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val title = postTitle
                        val body = postBody
                        val image = postImage
                        val audio = postAudio
                        submissionUi.draftTitle = title
                        submissionUi.draftBody = body
                        submissionUi.draftImage = image
                        submissionUi.draftAudio = audio
                        submissionUi.uploading = true
                        submissionUi.failed = false
                        submissionUi.notice = "Uploading your post…"
                        submissionUi.submittedConversationId = null
                        submissionUi.optimisticPost = LocalPendingPostUi(
                            title = title.trim(),
                            body = body.trim(),
                            authorName = ownDisplayName.ifBlank { "You" },
                            status = "Uploading…",
                        )
                        showCreatePost = false
                        onCreatePost?.invoke(title, body, image, audio) { ok, conversationId ->
                            submissionUi.uploading = false
                            if (ok) {
                                postTitle = ""
                                postBody = ""
                                postImage = null
                                postAudio = null
                                submissionUi.draftTitle = ""
                                submissionUi.draftBody = ""
                                submissionUi.draftImage = null
                                submissionUi.draftAudio = null
                                submissionUi.submittedConversationId = conversationId
                                submissionUi.optimisticPost = submissionUi.optimisticPost?.copy(
                                    conversationId = conversationId,
                                    status = "Not visible yet · waiting for the selected moderation branch",
                                    failed = false,
                                )
                                submissionUi.notice = "Post uploaded and submitted."
                                submissionUi.failed = false
                            } else {
                                submissionUi.optimisticPost = submissionUi.optimisticPost?.copy(
                                    status = "Upload failed · reopen the composer to try again",
                                    failed = true,
                                )
                                submissionUi.notice = "Post upload failed. Your draft is still here if you reopen the composer."
                                submissionUi.failed = true
                            }
                        }
                    },
                    enabled = postTitle.isNotBlank() && postBody.isNotBlank() &&
                        !importingPostImage && !importingPostAudio,
                ) { Text("Post") }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePost = false }) { Text("Cancel") }
            },
        )
    }

    banTarget?.let { author ->
        AlertDialog(
            onDismissRequest = { if (!moderationBusy) banTarget = null },
            title = { Text("Ban user from this branch?") },
            text = {
                Text(
                    "${author.authorName.ifBlank { "This user" }} will have new submissions rejected by this moderation branch. Other branches remain independent."
                )
            },
            confirmButton = {
                Button(
                    enabled = !moderationBusy && onBanAuthor != null,
                    onClick = {
                        moderationBusy = true
                        onBanAuthor?.invoke(author.authorId) { ok, message ->
                            moderationBusy = false
                            if (ok) banTarget = null
                            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text(if (moderationBusy) "Banning…" else "Ban user", maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(enabled = !moderationBusy, onClick = { banTarget = null }) { Text("Cancel") }
            },
        )
    }

    deleteTarget?.let { target ->
        val rootPreview = target.rootMessage
        AlertDialog(
            onDismissRequest = { if (!moderationBusy) deleteTarget = null },
            title = { Text("Remove this post?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The source post is not destroyed. It is removed from this moderation branch.")
                    OutlinedTextField(
                        value = deleteReason,
                        onValueChange = { deleteReason = it.take(600) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Public reason (optional)") },
                        minLines = 2,
                        maxLines = 5,
                    )
                    Text(
                        "The reason is shown with the removal notice so viewers can see why this branch removed it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !moderationBusy && rootPreview?.fullMessage != null && onDeletePost != null,
                    onClick = {
                        val ref = rootPreview?.fullMessage ?: return@Button
                        moderationBusy = true
                        onDeletePost?.invoke(target.conversation.objectId, ref, deleteReason) { ok, message ->
                            moderationBusy = false
                            if (ok) {
                                deleteTarget = null
                                selected?.let { onRefreshBranch?.invoke(it) }
                            }
                            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text(if (moderationBusy) "Removing…" else "Remove post", maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(enabled = !moderationBusy, onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    modifyTarget?.let { target ->
        val rootPreview = target.rootMessage
        AlertDialog(
            onDismissRequest = { if (!moderationBusy) modifyTarget = null },
            title = { Text("Modify for this curated version") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This creates a branch-local curated copy. The author's original source object is not modified.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = modifyTitle,
                        onValueChange = { modifyTitle = it.take(160) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Title") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = modifyBody,
                        onValueChange = { modifyBody = it.take(MAX_COMMENT_CHARS) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Post") },
                        minLines = 4,
                        maxLines = 10,
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !moderationBusy && modifyTitle.isNotBlank() && modifyBody.isNotBlank() &&
                        rootPreview?.fullMessage != null && onModifyPost != null,
                    onClick = {
                        val ref = rootPreview?.fullMessage ?: return@Button
                        moderationBusy = true
                        onModifyPost?.invoke(
                            target.conversation.objectId,
                            ref,
                            modifyTitle,
                            modifyBody,
                        ) { ok, message ->
                            moderationBusy = false
                            if (ok) {
                                modifyTarget = null
                                selected?.let { onRefreshBranch?.invoke(it) }
                            }
                            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text(if (moderationBusy) "Saving…" else "Save curated copy", maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(enabled = !moderationBusy, onClick = { modifyTarget = null }) { Text("Cancel") }
            },
        )
    }

    if (showModeratorDialog) {
        AlertDialog(
            onDismissRequest = { showModeratorDialog = false },
            title = { Text("Add branch moderator") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = moderatorKey,
                        onValueChange = { moderatorKey = it.trim().take(512) },
                        label = { Text("Moderator main DHT") },
                        singleLine = true,
                    )
                    PermissionCheck("Moderate posts/comments", modPosts) { modPosts = it }
                    PermissionCheck("Approve members", modMembers) { modMembers = it }
                    PermissionCheck("Handle reports", modReports) { modReports = it }
                    PermissionCheck("Pin comments", modPin) { modPin = it }
                    PermissionCheck("Edit featured area", modFeatured) { modFeatured = it }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (moderatorKey.isNotBlank()) {
                            onAddModerator?.invoke(
                                GroupModeratorGrant(
                                    moderatorMainDht = moderatorKey,
                                    canModeratePosts = modPosts,
                                    canApproveMembers = modMembers,
                                    canHandleReports = modReports,
                                    canPinPosts = modPin,
                                    canEditFeatured = modFeatured,
                                    grantedAt = 0L,
                                )
                            )
                        }
                        moderatorKey = ""
                        showModeratorDialog = false
                    },
                    enabled = moderatorKey.isNotBlank(),
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showModeratorDialog = false }) { Text("Cancel") } },
        )
    }

    if (showFeaturedDialog) {
        val candidates = posts.flatMap { post ->
            listOfNotNull(post.rootMessage) + post.recentMessages
        }
        AlertDialog(
            onDismissRequest = { showFeaturedDialog = false },
            title = { Text("Featured area") },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TextButton(
                        onClick = {
                            onSetFeatured?.invoke(FeaturedSlot())
                            showFeaturedDialog = false
                        }
                    ) { Text("Clear featured area") }

                    OutlinedTextField(
                        value = customFeaturedTitle,
                        onValueChange = { customFeaturedTitle = it.take(120) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Custom title") },
                    )
                    OutlinedTextField(
                        value = customFeaturedBody,
                        onValueChange = { customFeaturedBody = it.take(1200) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Custom message") },
                        minLines = 2,
                    )
                    Button(
                        onClick = {
                            onSetFeatured?.invoke(
                                FeaturedSlot(
                                    kind = FeaturedKind.Message,
                                    title = customFeaturedTitle,
                                    body = customFeaturedBody,
                                )
                            )
                            showFeaturedDialog = false
                        },
                        enabled = customFeaturedTitle.isNotBlank() || customFeaturedBody.isNotBlank(),
                    ) { Text("Use custom message") }

                    if (candidates.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Or feature a post/comment", fontWeight = FontWeight.SemiBold)
                        candidates.take(24).forEach { message ->
                            TextButton(
                                onClick = {
                                    onSetFeatured?.invoke(
                                        FeaturedSlot(
                                            kind = FeaturedKind.Post,
                                            ref = message.fullMessage,
                                            title = message.title ?: "Featured comment",
                                            body = "${message.authorName}: ${message.bodyPreview}",
                                        )
                                    )
                                    showFeaturedDialog = false
                                }
                            ) {
                                Text(
                                    buildString {
                                        message.title?.let { append(it).append(" — ") }
                                        append(message.authorName).append(": ").append(message.bodyPreview)
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFeaturedDialog = false }) { Text("Close") } },
        )
    }
}

@Composable
fun GroupPostScreen(
    store: GroupStore,
    media: LocalMediaStore,
    controller: SocialNetworkController,
    groupId: String,
    conversationId: String,
    ownMainDht: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPostComment: (String) -> Unit,
    onPinComment: (String, Boolean) -> Unit,
    onSetFeatured: (FeaturedSlot) -> Unit,
    onOpenLink: (DetectedLink) -> Unit,
) {
    val context = LocalContext.current
    val revision = store.revision
    val group = remember(revision, groupId) { store.byId(groupId) }
    val selected = remember(revision, groupId) { store.selectedBranch(groupId) }
    val selectedHeader = remember(revision, groupId, selected?.branchId) { store.selectedHeader(groupId) }
    val detail = remember(revision, groupId, conversationId, selected?.branchId) {
        selected?.branchId?.let { store.postDetail(groupId, it, conversationId) }
    }

    val ownGrant = selectedHeader?.moderators?.firstOrNull { it.moderatorMainDht == ownMainDht }
    val ownsBranch = selected?.ownerMainDht == ownMainDht ||
        (selected == null && group?.ownerId == ownMainDht)
    val canPin = ownsBranch || ownGrant?.canPinPosts == true
    val canFeature = ownsBranch || ownGrant?.canEditFeatured == true
    val canComment = group?.joined == true || group?.ownerId == ownMainDht

    var commentText by remember { mutableStateOf("") }

    val currentPostRefresh by rememberUpdatedState(onRefresh)
    LaunchedEffect(groupId, conversationId, selected?.branchId) {
        currentPostRefresh()
        while (true) {
            delay(GROUP_OPEN_REFRESH_MS)
            currentPostRefresh()
        }
    }

    val root = detail?.root
    val comments = detail?.comments.orEmpty()

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("\u2190") }
                Column(Modifier.weight(1f)) {
                    Text(
                        root?.message?.title ?: "Post",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    group?.let {
                        Text(
                            it.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val postLink = group?.let { encodeGroupPostLink(it, conversationId) }
                if (postLink != null) {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Weave post", postLink))
                    }) { Text("🔗") }
                }
                if (canFeature && root != null) {
                    TextButton(
                        onClick = {
                            onSetFeatured(
                                FeaturedSlot(
                                    kind = FeaturedKind.Post,
                                    ref = root.ref,
                                    title = root.message.title ?: "Featured post",
                                    body = "${root.message.authorName}: ${root.message.body.take(320)}",
                                )
                            )
                        }
                    ) { Text("Feature") }
                }
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            if (root == null) {
                item { HintText("Loading post…") }
            } else {
                item {
                    ElevatedCard(Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                root.message.authorName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            root.message.title?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                            if (!root.message.curatedBy.isNullOrBlank()) {
                                Text(
                                    "Modified for this curated version · original source unchanged",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                            FilteredAutoLinkText(
                                contentId = "group-message:${root.message.messageId}",
                                text = root.message.body,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp),
                                onLink = onOpenLink,
                            )
                            PostAttachmentViewer(root.message, media, controller)
                            root.message.fullMedia
                                .filter { it.type == WeaveObjectType.Audio }
                                .forEachIndexed { index, audio ->
                                    WeaveAudioPlayer(
                                        title = if (root.message.fullMedia.count { it.type == WeaveObjectType.Audio } > 1)
                                            "Audio ${index + 1}" else "Audio",
                                        contentHash = audio.objectId,
                                        recordKey = audio.recordKey,
                                        media = media,
                                        controller = controller,
                                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                                    )
                                }
                        }
                    }
                }

                item {
                    Text(
                        "Comments",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                if (comments.isEmpty()) {
                    item { HintText("No comments yet.") }
                } else {
                    items(comments, key = { it.message.messageId }) { view ->
                        GroupFullCommentRow(
                            view = view,
                            canPin = canPin,
                            canFeature = canFeature,
                            onPin = { onPinComment(view.message.messageId, !view.pinned) },
                            onFeature = {
                                onSetFeatured(
                                    FeaturedSlot(
                                        kind = FeaturedKind.Post,
                                        ref = view.ref,
                                        title = "Featured comment",
                                        body = "${view.message.authorName}: ${view.message.body.take(320)}",
                                    )
                                )
                            },
                            onOpenLink = onOpenLink,
                        )
                        HorizontalDivider()
                    }
                }

                if (canComment) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = commentText,
                                onValueChange = { commentText = it.take(MAX_COMMENT_CHARS) },
                                modifier = Modifier.weight(1f),
                                label = { Text("Leave a comment") },
                                minLines = 1,
                                maxLines = 5,
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val body = commentText.trim()
                                    if (body.isNotBlank()) {
                                        onPostComment(body)
                                        commentText = ""
                                    }
                                },
                                enabled = commentText.isNotBlank(),
                            ) { Text("Post") }
                        }
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun GroupFullCommentRow(
    view: GroupMessageView,
    canPin: Boolean,
    canFeature: Boolean,
    onPin: () -> Unit,
    onFeature: () -> Unit,
    onOpenLink: (DetectedLink) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        TinyThumbnail(view.message.thumbnailBase64, "group-thumb:${view.message.messageId}")
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(view.message.authorName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                if (view.pinned) Text(" \uD83D\uDCCC", style = MaterialTheme.typography.labelSmall)
            }
            FilteredAutoLinkText(
                contentId = "group-message:${view.message.messageId}",
                text = view.message.body,
                style = MaterialTheme.typography.bodyMedium,
                onLink = onOpenLink,
            )
        }
        if (canPin) {
            TextButton(onClick = onPin) { Text(if (view.pinned) "Unpin" else "Pin") }
        }
        if (canFeature) {
            TextButton(onClick = onFeature) { Text("Feature") }
        }
    }
}

@Composable
private fun GroupPostCard(
    post: GroupConversationPreview,
    canModerate: Boolean = false,
    onClick: () -> Unit,
    onBan: () -> Unit = {},
    onDelete: () -> Unit = {},
    onModify: () -> Unit = {},
) {
    val root = post.rootMessage ?: return
    var menu by remember(post.conversation.objectId) { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            TinyThumbnail(root.thumbnailBase64, "group-thumb:${root.messageId}")
            Column(Modifier.weight(1f)) {
                Text(post.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        root.authorName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (root.hasAudio) {
                        Text(
                            " · 🎵 Audio",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                if (!root.curatedBy.isNullOrBlank()) {
                    Text(
                        "Modified for this curated version · source post unchanged",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                FilteredPlainText(
                    contentId = "group-preview:${root.messageId}",
                    text = root.bodyPreview,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "${post.replyCount} comment${if (post.replyCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (canModerate) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Text("☰", style = MaterialTheme.typography.titleMedium)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Ban user") },
                            onClick = { menu = false; onBan() },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete this post") },
                            onClick = { menu = false; onDelete() },
                        )
                        DropdownMenuItem(
                            text = { Text("Modify this post") },
                            onClick = { menu = false; onModify() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingLocalPostCard(post: LocalPendingPostUi) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(post.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (!post.failed && post.conversationId == null) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            Text(
                post.authorName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(post.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            Text(
                post.status,
                style = MaterialTheme.typography.labelSmall,
                color = if (post.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun CuratorPostCard(post: GroupCuratorPost) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            val message = post.message
            Text(
                message?.title?.takeIf { it.isNotBlank() } ?: "Retained post",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                message?.authorName?.takeIf { it.isNotBlank() } ?: shortBranchOwner(post.authorMainDht),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (message != null) {
                Text(
                    message.body,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                Text(
                    "The retained event is known, but its payload is not currently available or failed hash verification.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Text(
                post.status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun RemovedPostCard(post: GroupRemovedPostNotice) {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(post.title.ifBlank { "Removed post" }, fontWeight = FontWeight.SemiBold)
            if (post.authorName.isNotBlank()) {
                Text(
                    "Originally posted by ${post.authorName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (post.reason.isBlank()) "Removed by this moderation branch." else "Removed: ${post.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun PostAttachmentViewer(
    message: WeaveMessage,
    media: LocalMediaStore,
    controller: SocialNetworkController,
) {
    val context = LocalContext.current
    val attachment = message.fullMedia.firstOrNull { it.type == WeaveObjectType.Image } ?: return
    var requested by remember(message.messageId) { mutableStateOf(false) }
    var loading by remember(message.messageId) { mutableStateOf(false) }
    var revision by remember(message.messageId) { mutableIntStateOf(0) }
    var error by remember(message.messageId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val fullBitmap = remember(revision, attachment.objectId) {
        attachment.objectId.takeIf { it.isNotBlank() }?.let { media.bitmapFor(it) }
    }
    val thumbBitmap = remember(message.thumbnailBase64) {
        message.thumbnailBase64?.let {
            runCatching {
                val bytes = Base64.decode(it, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        if (!requested) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !loading) {
                    requested = true
                    if (fullBitmap == null && attachment.recordKey.isNotBlank() && attachment.objectId.isNotBlank()) {
                        loading = true
                        error = null
                        scope.launch {
                            val bytes = controller.downloadMedia(
                                rootRecordKey = attachment.recordKey,
                                expectedSha256Hex = attachment.objectId,
                                maxBytes = MAX_REMOTE_IMAGE_BYTES,
                            )
                            if (bytes == null || !media.storeVerifiedBytes(attachment.objectId, bytes)) {
                                error = "Couldn't load the full image."
                            } else {
                                revision++
                            }
                            loading = false
                        }
                    }
                }
            ) {
                Column(Modifier.padding(10.dp)) {
                    if (thumbBitmap != null) {
                        FilteredImage(
                            contentId = "group-image:${attachment.objectId}",
                            bitmap = thumbBitmap,
                            modifier = Modifier.fillMaxWidth(),
                        ) { gateModifier ->
                            Image(
                                bitmap = thumbBitmap.asImageBitmap(),
                                contentDescription = "Post image thumbnail",
                                modifier = gateModifier.fillMaxWidth().heightIn(max = 220.dp),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                    if (loading) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Loading full image…", style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        Text(
                            if (thumbBitmap == null) "Tap to load image" else "Tap thumbnail to load full image",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    encodeMediaLink(attachment)?.let { link ->
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Weave image", link))
                        }) { Text("Copy image link") }
                    }
                }
            }
        } else {
            when {
                fullBitmap != null -> FilteredImage(
                    contentId = "group-image:${attachment.objectId}",
                    bitmap = fullBitmap,
                    modifier = Modifier.fillMaxWidth(),
                ) { gateModifier ->
                    Image(
                        bitmap = fullBitmap,
                        contentDescription = "Post image",
                        modifier = gateModifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit,
                    )
                }
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Loading full image…", style = MaterialTheme.typography.labelSmall)
                }
                else -> Text(
                    error ?: "Couldn't load the full image.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PermissionCheck(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label)
    }
}

@Composable
private fun GroupCommentRow(
    message: MessagePreview,
    canPin: Boolean,
    canFeature: Boolean,
    onPin: () -> Unit,
    onFeature: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        TinyThumbnail(message.thumbnailBase64, "group-thumb:${message.messageId}")
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message.authorName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                if (message.pinned) Text(" \uD83D\uDCCC", style = MaterialTheme.typography.labelSmall)
            }
            FilteredPlainText(
                contentId = "group-preview:${message.messageId}",
                text = message.bodyPreview,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (canPin) {
            TextButton(onClick = onPin) { Text(if (message.pinned) "Unpin" else "Pin") }
        }
        if (canFeature) {
            TextButton(onClick = onFeature) { Text("Feature") }
        }
    }
}

private fun shortBranchOwner(value: String): String =
    if (value.length <= 18) value else "${value.take(8)}…${value.takeLast(6)}"

@Composable
fun GroupEditorScreen(
    store: GroupStore,
    media: LocalMediaStore,
    ownKey: String,
    groupId: String?,
    onPublish: (GroupRecord, GroupPulse) -> Unit,
    onSaved: (String) -> Unit,
    onBack: () -> Unit,
) {
    val original = remember(store.revision, groupId) { groupId?.let(store::byId) }
    var name by remember(original?.groupId) { mutableStateOf(original?.name.orEmpty()) }
    var description by remember(original?.groupId) { mutableStateOf(original?.description.orEmpty()) }
    var thumbnailBase64 by remember(original?.groupId) { mutableStateOf(original?.thumbnailBase64) }
    var thumbnailImporting by remember(original?.groupId) { mutableStateOf(false) }
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
    val scope = rememberCoroutineScope()
    val thumbnailPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        thumbnailImporting = true
        scope.launch {
            val stored = media.importImage(uri, maxEdge = 512, quality = 78)
            var thumb = stored?.let { media.thumbnailBase64For(it.contentHash, maxEdge = 112, quality = 55) }
            if (thumb != null && thumb.length > MAX_GROUP_THUMBNAIL_BASE64_CHARS) {
                thumb = stored?.let { media.thumbnailBase64For(it.contentHash, maxEdge = 80, quality = 45) }
            }
            thumbnailBase64 = thumb?.takeIf { it.length <= MAX_GROUP_THUMBNAIL_BASE64_CHARS }
            thumbnailImporting = false
        }
    }

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupThumbnail(
                    encoded = thumbnailBase64,
                    groupId = original?.groupId ?: "new-group",
                    name = name.ifBlank { "Group" },
                    size = 72.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    OutlinedButton(
                        onClick = {
                            thumbnailPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        enabled = !thumbnailImporting,
                    ) {
                        if (thumbnailImporting) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Preparing…", maxLines = 1, softWrap = false)
                        } else {
                            Text(
                                if (thumbnailBase64 == null) "Add group picture" else "Change group picture",
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (thumbnailBase64 != null) {
                        TextButton(onClick = { thumbnailBase64 = null }) { Text("Remove") }
                    }
                }
            }
            Text(
                "A small embedded thumbnail helps distinguish groups without loading a full-size image.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                        store.createGroup(
                            ownerId = ownKey,
                            name = name,
                            description = description,
                            thumbnailBase64 = thumbnailBase64,
                            tags = parsedTags,
                            policy = policy,
                            featured = featured,
                        )
                    } else {
                        original.copy(
                            name = name.trim().ifBlank { original.name },
                            description = description.trim(),
                            thumbnailBase64 = thumbnailBase64,
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
            GroupThumbnail(group.thumbnailBase64, group.groupId, group.name, size = 48.dp)
            Spacer(Modifier.width(10.dp))
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
                    conversation.rootMessage?.let { root ->
                        MessagePreviewLine(root)
                    }
                    Text(
                        "${conversation.replyCount} comment${if (conversation.replyCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 5.dp),
                    )
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
            conversation.rootMessage?.let { MessagePreviewLine(it) }
            Text(
                "${conversation.replyCount} comments · ${conversation.recentUniqueAuthors} participants",
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
        TinyThumbnail(message.thumbnailBase64, "group-thumb:${message.messageId}")
        Column(Modifier.weight(1f)) {
            Text(message.authorName, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            FilteredPlainText(
                contentId = "group-preview:${message.messageId}",
                text = message.bodyPreview,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GroupThumbnail(
    encoded: String?,
    groupId: String,
    name: String,
    size: androidx.compose.ui.unit.Dp = 48.dp,
) {
    val bitmap = remember(encoded) {
        encoded?.let {
            runCatching {
                val bytes = Base64.decode(it, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
    }
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (bitmap != null) {
            FilteredImage(
                contentId = "group-icon:$groupId:${encoded.hashCode()}",
                bitmap = bitmap,
                modifier = Modifier.fillMaxSize(),
                compact = true,
            ) { gateModifier ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "$name group thumbnail",
                    modifier = gateModifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "G",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun TinyThumbnail(encoded: String?, contentId: String) {
    val bitmap = remember(encoded) {
        encoded?.let {
            runCatching {
                val bytes = Base64.decode(it, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
    }
    if (bitmap != null) {
        FilteredImage(
            contentId = "$contentId:${encoded.hashCode()}",
            bitmap = bitmap,
            modifier = Modifier.size(52.dp).padding(end = 8.dp),
            compact = true,
        ) { gateModifier ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Message thumbnail",
                modifier = gateModifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun FeaturedCard(
    slot: FeaturedSlot,
    canEdit: Boolean = false,
    onEdit: () -> Unit = {},
) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (slot.kind == FeaturedKind.None) "Featured" else when (slot.kind) {
                        FeaturedKind.Post -> "\uD83D\uDCCC Featured comment"
                        FeaturedKind.Widget -> "Featured widget"
                        FeaturedKind.Message -> "Featured"
                        FeaturedKind.None -> "Featured"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (canEdit) TextButton(onClick = onEdit) { Text("Edit") }
            }
            if (slot.kind == FeaturedKind.None) {
                Text(
                    "Nothing featured yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (slot.title.isNotBlank()) {
                    Text(slot.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                if (slot.body.isNotBlank()) {
                    Text(slot.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
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
        GroupThumbnail(group.thumbnailBase64, group.groupId, group.name, size = 46.dp)
        Spacer(Modifier.width(12.dp))
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
        GroupThumbnail(group.thumbnailBase64, group.groupId, group.name, size = 46.dp)
        Spacer(Modifier.width(12.dp))
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
