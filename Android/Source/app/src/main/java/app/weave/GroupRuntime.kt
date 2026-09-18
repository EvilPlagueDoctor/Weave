package app.weave

import android.util.Base64
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * High-level group protocol coordinator used by SocialNetworkController.
 *
 * It deliberately keeps discovery, branch DHT state and transport separate:
 *   - directories are found through people;
 *   - branch DHTs are authoritative moderated views;
 *   - direct/mailbox/ServiceRequest traffic is only delivery/intake.
 */
class GroupRuntime(
    private val client: DaemonClient,
    private val store: GroupStore,
    private val profileStoreId: () -> String?,
    private val ownMainDht: () -> String,
    private val eventStoreV2: GroupEventStoreV2? = null,
    private val custodyStoreV2: GroupCustodyStoreV2? = null,
    private val trustStoreV2: GroupTrustContinuityStoreV2? = null,
    private val custodyCandidates: () -> List<String> = { emptyList() },
    private val logger: (String) -> Unit = {},
) {
    private val branches = GroupBranchNetwork(client, store, logger)
    private val branchDecisionInFlight = ConcurrentHashMap.newKeySet<String>()
    private val contentValidationQueued = ConcurrentHashMap.newKeySet<String>()
    private val directory = GroupDirectoryNetwork(client)
    private val content = GroupContentNetwork(client)
    private val closed = AtomicBoolean(false)
    private val sessionMainDht = ownMainDht()
    private val outboundRecoveryRequests = ConcurrentHashMap<String, OutboundCustodyRecoveryV2>()
    private val lastCustodyRecoveryStartedAt = AtomicLong(0L)
    private val abuseGuardV2 = GroupAbuseGuardV2(logger)
    private val custodyPool = ThreadPoolExecutor(
        CUSTODY_WORKERS, CUSTODY_WORKERS, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(CUSTODY_WORK_QUEUE),
        { runnable -> Thread(runnable, "Weave-GroupsV2-Custody").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val contentRecoveryScheduler = Executors.newScheduledThreadPool(CONTENT_RECOVERY_WORKERS) { runnable ->
        Thread(runnable, "Weave-GroupsV2-Recovery").apply { isDaemon = true }
    }
    private val contentFetchPool = Executors.newFixedThreadPool(CONTENT_FETCH_WORKERS) { runnable ->
        Thread(runnable, "Weave-GroupsV2-Fetch").apply { isDaemon = true }
    }

    private fun recordCanonicalSubmissionV2(
        groupId: String,
        eventType: GroupEventTypeV2,
        message: WeaveMessage,
        ref: WeaveObjectRef,
        parentObjectId: String = "",
    ): StoredGroupEventV2? {
        val storeV2 = eventStoreV2 ?: return null
        val hash = groupV2MessageHash(message)
        logger("[groups-v2] PAYLOAD_WRITTEN group=${short(groupId)} object=${short(message.messageId)} record=${short(ref.recordKey)} hash=${hash.take(16)}")
        val selectedBranch = store.selectedBranch(groupId)?.branchId
        val localBranchIds = mutableListOf<String>()
        store.branchesFor(groupId)
            .filter { it.ownerMainDht == ownMainDht() }
            .forEach { localBranchIds += it.branchId }
        selectedBranch?.let { localBranchIds += it }
        val branchIds = localBranchIds.distinct().take(64)
        val stored = storeV2.createLocalEvent(
            eventType = eventType,
            groupId = groupId,
            payload = GroupPayloadReferenceV2(
                objectType = ref.type,
                recordKey = ref.recordKey,
                subkey = ref.subkey,
                objectId = ref.objectId,
                parentObjectId = parentObjectId,
                schemaVersion = message.version,
            ),
            contentHashHex = hash,
            // Group-scoped targeting deliberately avoids enumerating every claim in the signed
            // event. Later transport/custody phases may use bounded fan-out even for huge groups.
            target = GroupEventTargetV2(GroupEventTargetModeV2.Group),
            branchIdsForLocalState = branchIds,
            createdAt = message.createdAt,
        )
        storeV2.verifyContentMessage(stored.event.eventId, message)
        mirrorBranchStatesV2(stored)
        return stored
    }

    fun publishOriginal(group: GroupRecord, pulse: GroupPulse): GroupRecord {
        if (group.policy.visibility == GroupVisibility.MembersOnly) {
            // Never leak an encrypted group's identity into public group/profile DHTs.
            // The private membership/key-distribution transport is intentionally separate.
            val local = group.copy(rootRecordKey = "")
            store.updateGroup(local)
            return local
        }
        val (published, header) = branches.publishOriginal(group, pulse, ownMainDht())
        store.rememberBranchHeaderAndKnown(header, ownedByMe = true)
        publishMyDirectory()
        return published
    }

    fun refreshBranch(pointer: GroupBranchPointer): Pair<GroupRecord, GroupPulse?>? {
        val header = branches.readHeader(pointer.branchRoot) ?: return null
        if (header.group.groupId != pointer.groupId) return null
        if (header.creatorRoot != pointer.creatorRoot) return null
        if (header.branchId != pointer.branchId) return null
        if (header.ownerMainDht != pointer.ownerMainDht) return null
        if (header.kind != pointer.kind) return null
        if (header.kind == GroupBranchKind.Original) {
            if (header.branchRoot != header.creatorRoot || header.ownerMainDht != header.group.ownerId) return null
        } else {
            if (header.group.rootRecordKey.isNotBlank() && header.group.rootRecordKey != header.creatorRoot) return null
        }
        store.rememberBranchHeaderAndKnown(header)
        reconcileFrom(header)
        val pulse = branches.readPulse(pointer.branchRoot)
        val effectiveGroup = header.group.copy(featured = header.featured)
        // upsertDiscovered persists the Pulse too; do not write it once here and then immediately
        // write the same encrypted Pulse a second time.
        store.upsertDiscovered(effectiveGroup, pulse, pointer.branchId)
        return effectiveGroup to pulse
    }

    fun refreshSelected(groupId: String): Pair<GroupRecord, GroupPulse?>? {
        val group = store.byId(groupId) ?: return null
        val selected = store.selectedBranch(groupId)
            ?: group.rootRecordKey.takeIf { it.isNotBlank() }?.let {
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
            ?: return null
        return refreshBranch(selected)
    }

    fun discoverUserGroups(profileMainDht: String, profileRoot: String): List<GroupDirectoryEntry> {
        if (profileMainDht.isBlank() || profileRoot.isBlank()) return emptyList()
        val entries = directory.read(profileRoot, profileMainDht)
        store.upsertDirectoryEntries(profileMainDht, entries)
        return entries
    }

    /**
     * Anyone may claim a public/unlisted group. Permission from the creator is intentionally not
     * requested; a claim exists precisely so users can choose a different moderation authority.
     */
    fun claim(groupId: String): GroupBranchHeader {
        val group = store.byId(groupId) ?: error("Unknown group")
        require(group.policy.visibility != GroupVisibility.MembersOnly) {
            "Encrypted/member-only groups cannot be claimed"
        }
        require(group.ownerId != ownMainDht()) { "The creator already owns the original branch" }
        store.ownClaim(groupId, ownMainDht())?.let { existing ->
            return branches.readHeader(existing.branchRoot) ?: error("Existing claim branch is unavailable")
        }
        val original = store.branchesFor(groupId).firstOrNull { it.kind == GroupBranchKind.Original }
            ?: GroupBranchPointer(
                groupId = groupId,
                branchId = GroupBranchNetwork.originalBranchId(groupId),
                branchRoot = group.rootRecordKey,
                ownerMainDht = group.ownerId,
                kind = GroupBranchKind.Original,
                creatorRoot = group.rootRecordKey,
                updatedAt = group.updatedAt,
            )
        require(original.branchRoot.isNotBlank()) { "Creator branch is unavailable" }
        val creatorHeader = branches.readHeader(original.branchRoot) ?: error("Could not verify creator branch")
        val claim = branches.createClaim(group, creatorHeader, ownMainDht())
        store.rememberBranchHeaderAndKnown(claim, ownedByMe = true)
        // Claiming is an explicit local choice: use the new moderation view immediately and keep
        // it selected across future Original-branch refreshes.
        store.selectBranch(groupId, claim.branchId)
        logger("groups: claim created group=${group.name} branch=${claim.branchId} owner=${ownMainDht()}")

        // If this device had already observed temporary/unreviewed intake for the group before it
        // became a claimer, attach those items to the new branch now. This is especially useful
        // when the original creator is offline: the new claimer can review the still-live intake
        // instead of waiting for the author to resubmit it.
        val inheritedPending = store.pendingFor(groupId)
        if (inheritedPending.isNotEmpty()) {
            logger("groups: claim adopting ${inheritedPending.size} still-live pending item(s) for ${group.name}")
        }
        inheritedPending.forEach { pending ->
            if (pending.contentRef != null && pending.event.kind in setOf(
                    GroupEventKind.PostSubmitted, GroupEventKind.ConversationCreated
                )) {
                maybeAutoAccept(claim.pointer(), GroupWireEnvelope(pending.event, pending.contentRef))
            }
        }

        // Tell known authorities that this alternate moderation branch exists. They do not approve it.
        val announcement = GroupEvent(
            groupId = groupId,
            branchId = claim.branchId,
            kind = GroupEventKind.ClaimCreated,
            actorMainDht = ownMainDht(),
            createdAt = System.currentTimeMillis(),
            objectId = claim.branchId,
            objectHash = groupSha256Hex(claim.pointer().toJson().toString().encodeToByteArray()),
            metadata = mapOf(
                "branch_root" to claim.branchRoot,
                "creator_root" to claim.creatorRoot,
            ),
        )
        val envelope = GroupWireEnvelope(announcement)
        branches.sendPrivateAuthorityEvent(envelope, store.branchesFor(groupId).filter { it.branchId != claim.branchId })
        publishMyDirectory()
        return claim
    }

    fun resolveGroupLink(raw: String): GroupRecord {
        val link = GroupLink.parse(raw) ?: error("Not a Weave group link")
        val header = branches.readHeader(link.creatorRoot) ?: error("Group root is unavailable")
        require(header.kind == GroupBranchKind.Original) { "Group link must point to the Original branch" }
        require(header.group.groupId == link.groupId) { "Group link ID does not match its DHT root" }
        require(header.creatorRoot == link.creatorRoot && header.branchRoot == link.creatorRoot) {
            "Group link does not point to its creator root"
        }
        store.rememberBranchHeaderAndKnown(header)
        val pulse = branches.readPulse(link.creatorRoot)
        val effective = header.group.copy(featured = header.featured)
        store.upsertDiscovered(effective, pulse, header.branchId)
        return effective
    }

    fun claimLink(raw: String): GroupBranchHeader {
        val group = resolveGroupLink(raw)
        return claim(group.groupId)
    }

    /** A true fork is intentionally a different group identity. */
    fun fork(groupId: String, newName: String): GroupRecord {
        val original = store.byId(groupId) ?: error("Unknown group")
        return store.createGroup(
            ownerId = ownMainDht(),
            name = newName.ifBlank { "${original.name} fork" },
            description = original.description,
            thumbnailBase64 = original.thumbnailBase64,
            tags = original.tags,
            policy = original.policy,
            featured = original.featured,
        )
    }

    fun submitConversation(groupId: String, conversation: WeaveObjectRef, title: String) {
        val group = store.byId(groupId) ?: error("Unknown group")
        val event = GroupEvent(
            groupId = groupId,
            branchId = "*",
            kind = GroupEventKind.ConversationCreated,
            actorMainDht = ownMainDht(),
            createdAt = System.currentTimeMillis(),
            objectId = conversation.objectId,
            objectHash = groupSha256Hex(conversation.toJson().toString().encodeToByteArray()),
            metadata = mapOf("title" to title.take(160)),
        )
        deliverSubmission(group, GroupWireEnvelope(event, conversation))
    }

    fun createGroupPost(
        groupId: String,
        title: String,
        body: String,
        authorName: String,
        thumbnailBase64: String? = null,
        fullMedia: List<WeaveObjectRef> = emptyList(),
    ): String {
        val cleanTitle = title.trim().take(160)
        val cleanBody = body.trim().take(MAX_COMMENT_CHARS)
        require(cleanTitle.isNotBlank()) { "Post title is empty" }
        require(cleanBody.isNotBlank()) { "Post body is empty" }
        val group = store.byId(groupId) ?: error("Unknown group")
        val postId = UUID.randomUUID().toString()
        val conversationId = postConversationId(postId)
        val message = WeaveMessage(
            messageId = postId,
            container = WeaveObjectRef(conversationId, type = WeaveObjectType.Conversation),
            authorId = ownMainDht(),
            authorName = authorName.ifBlank { "Someone" }.take(80),
            body = cleanBody,
            title = cleanTitle,
            thumbnailBase64 = thumbnailBase64,
            fullMedia = fullMedia,
            createdAt = System.currentTimeMillis(),
        )
        val ref = content.publish(message)
        val storedV2 = recordCanonicalSubmissionV2(
            groupId = groupId,
            eventType = GroupEventTypeV2.PostSubmitted,
            message = message,
            ref = ref,
        ) ?: error("Groups v2 event store is unavailable")
        deliverCanonicalSubmissionV2(group, storedV2.event)
        processCanonicalForOwnedBranches(storedV2, message, GroupEventTransportV2.LocalCreated, ownMainDht())
        return conversationId
    }

    fun postComment(
        groupId: String,
        conversationId: String,
        body: String,
        authorName: String,
    ): WeaveMessage {
        require(conversationId.startsWith("post:")) { "Comments must belong to a group post" }
        val cleanBody = body.trim().take(MAX_COMMENT_CHARS)
        require(cleanBody.isNotBlank()) { "Comment is empty" }
        val group = store.byId(groupId) ?: error("Unknown group")
        val selected = store.selectedBranch(groupId) ?: error("No selected moderation branch")
        val rootId = postIdFromConversation(conversationId)
        val rootRef = store.postDetail(groupId, selected.branchId, conversationId)?.root?.ref
            ?: branches.readVisibleEntries(selected.branchRoot, conversationId, 500)
                .firstOrNull { it.postId == rootId }
                ?.messageRef
            ?: error("The root post is not visible on this moderation branch")

        val message = WeaveMessage(
            messageId = UUID.randomUUID().toString(),
            container = WeaveObjectRef(conversationId, type = WeaveObjectType.Conversation),
            authorId = ownMainDht(),
            authorName = authorName.ifBlank { "Someone" }.take(80),
            body = cleanBody,
            replyTo = rootRef,
            root = rootRef,
            createdAt = System.currentTimeMillis(),
        )
        val ref = content.publish(message)
        val storedV2 = recordCanonicalSubmissionV2(
            groupId = groupId,
            eventType = GroupEventTypeV2.CommentSubmitted,
            message = message,
            ref = ref,
            parentObjectId = conversationId,
        ) ?: error("Groups v2 event store is unavailable")
        deliverCanonicalSubmissionV2(group, storedV2.event)
        processCanonicalForOwnedBranches(storedV2, message, GroupEventTransportV2.LocalCreated, ownMainDht())
        return message
    }

    fun loadMessage(ref: WeaveObjectRef): WeaveMessage? = content.fetch(ref)

    /**
     * Publish a branch-local replacement view of a post. The author's immutable source object is
     * left untouched; the branch index simply points at a new message object that is explicitly
     * marked as curator-modified and records the hash of the source it came from.
     */
    fun modifyPostForBranch(
        groupId: String,
        conversationId: String,
        sourceRef: WeaveObjectRef,
        title: String,
        body: String,
    ): WeaveObjectRef {
        val source = content.fetch(sourceRef) ?: error("The source post could not be loaded")
        require(source.messageId == postIdFromConversation(conversationId)) { "This is not the root post" }
        val cleanTitle = title.trim().take(160)
        val cleanBody = body.trim().take(MAX_COMMENT_CHARS)
        require(cleanTitle.isNotBlank()) { "Post title is empty" }
        require(cleanBody.isNotBlank()) { "Post body is empty" }
        val curated = source.copy(
            title = cleanTitle,
            body = cleanBody,
            editedAt = System.currentTimeMillis(),
            curatedBy = ownMainDht(),
            curatedFromHash = source.curatedFromHash ?: weaveMessageDigest(source),
            signature = "",
        )
        val curatedRef = content.publish(curated)
        moderatePost(
            groupId = groupId,
            postId = curated.messageId,
            conversationId = conversationId,
            postHash = weaveMessageDigest(curated),
            state = GroupPostState.Visible,
            messageRef = curatedRef,
            reason = "Curator-modified branch copy; original source unchanged",
        )
        return curatedRef
    }

    /** Remove the root post from the selected moderation branch without touching its source object. */
    fun removePostForBranch(
        groupId: String,
        conversationId: String,
        sourceRef: WeaveObjectRef,
        reason: String = "",
    ) {
        val source = content.fetch(sourceRef) ?: error("The source post could not be loaded")
        require(source.messageId == postIdFromConversation(conversationId)) { "This is not the root post" }
        moderatePost(
            groupId = groupId,
            postId = source.messageId,
            conversationId = conversationId,
            postHash = weaveMessageDigest(source),
            state = GroupPostState.Removed,
            messageRef = null,
            reason = reason.trim().take(600),
        )
    }

    fun banAuthor(groupId: String, authorMainDht: String) {
        require(authorMainDht.isNotBlank()) { "Author identity is unavailable" }
        val selected = store.selectedBranch(groupId) ?: error("No moderation branch selected")
        if (selected.ownerMainDht == ownMainDht()) {
            val header = branches.readHeader(selected.branchRoot) ?: error("Branch unavailable")
            branches.banAuthor(header, authorMainDht, ownMainDht())
        } else {
            branches.sendPrivateAuthorityEvent(
                GroupWireEnvelope(
                    GroupEvent(
                        groupId = groupId,
                        branchId = selected.branchId,
                        kind = GroupEventKind.MemberBanned,
                        actorMainDht = ownMainDht(),
                        createdAt = System.currentTimeMillis(),
                        objectId = authorMainDht,
                    )
                ),
                listOf(selected),
            )
        }
    }

    /**
     * Human-readable curator/custody view. This intentionally reads the canonical event store
     * rather than a moderation branch so people can inspect submissions that are still retained
     * even when the selected branch has not exposed them.
     */
    fun curatorPosts(groupId: String, limit: Int = 48): List<GroupCuratorPost> {
        val now = System.currentTimeMillis()
        return eventStoreV2?.eventsForGroup(groupId).orEmpty()
            .asSequence()
            .filter { it.event.eventType == GroupEventTypeV2.PostSubmitted }
            .filter { it.event.expiresAt <= 0L || it.event.expiresAt > now }
            .filter { it.validation == GroupEventValidationV2.Valid || it.validation == GroupEventValidationV2.Unvalidated }
            .sortedByDescending { it.event.createdAt }
            .take(limit.coerceIn(1, 100))
            .map { stored ->
                val states = stored.branchStates.values
                val status = when {
                    states.any { it == GroupBranchEventStateV2.Pending } -> "Pending moderation"
                    states.any { it == GroupBranchEventStateV2.Accepted } -> "Accepted by a moderation branch"
                    states.any { it == GroupBranchEventStateV2.Rejected } -> "Rejected by a moderation branch"
                    stored.evidence.keys.any { it == GroupEventTransportV2.CustodyGossip || it == GroupEventTransportV2.CustodyRecovery } -> "Held by a curator"
                    else -> "Retained submission"
                }
                GroupCuratorPost(
                    eventId = stored.event.eventId,
                    conversationId = postConversationId(stored.event.payload.objectId),
                    authorMainDht = stored.event.authorMainDht,
                    createdAt = stored.event.createdAt,
                    status = status,
                    message = if (stored.contentState == GroupEventContentStateV2.HashMismatch) null
                    else content.fetch(stored.event.payload.toWeaveRef()),
                )
            }
            .toList()
    }

    fun hydratePost(groupId: String, conversationId: String): GroupPostDetail? {
        val selected = store.selectedBranch(groupId) ?: return null
        val header = branches.readHeader(selected.branchRoot) ?: return null
        store.rememberBranchHeader(header)
        val entries = branches.readVisibleEntries(header.branchRoot, conversationId, 500)
        if (entries.isEmpty()) return null

        val rootId = postIdFromConversation(conversationId)
        var rootView: GroupMessageView? = null
        val commentViews = mutableListOf<GroupMessageView>()
        entries.forEach { entry ->
            val ref = entry.messageRef ?: return@forEach
            val message = content.fetch(ref) ?: return@forEach
            val view = GroupMessageView(
                message = message,
                ref = ref,
                pinned = message.messageId in header.pinnedPostIds,
            )
            if (message.messageId == rootId && message.root == null) rootView = view
            else commentViews += view
        }
        val root = rootView ?: return null
        val sortedCommentViews = commentViews.sortedWith(
            compareByDescending<GroupMessageView> { it.pinned }.thenBy { it.message.createdAt }
        )
        val detail = GroupPostDetail(
            groupId = groupId,
            branchId = header.branchId,
            conversationId = conversationId,
            root = root,
            comments = sortedCommentViews,
        )
        store.rememberPostDetail(detail)

        val rootPreview = MessagePreview(
            messageId = root.message.messageId,
            authorId = root.message.authorId,
            authorName = root.message.authorName,
            bodyPreview = root.message.body.take(320),
            title = root.message.title,
            thumbnailBase64 = root.message.thumbnailBase64,
            fullMessage = root.ref,
            createdAt = root.message.createdAt,
            pinned = root.pinned,
            hasAudio = root.message.fullMedia.any { it.type == WeaveObjectType.Audio },
        )
        val commentPreviews = sortedCommentViews.map { view ->
            MessagePreview(
                messageId = view.message.messageId,
                authorId = view.message.authorId,
                authorName = view.message.authorName,
                bodyPreview = view.message.body.take(320),
                title = view.message.title,
                thumbnailBase64 = view.message.thumbnailBase64,
                fullMessage = view.ref,
                createdAt = view.message.createdAt,
                pinned = view.pinned,
                hasAudio = view.message.fullMedia.any { it.type == WeaveObjectType.Audio },
            )
        }
        val currentPulse = store.pulse(groupId)
        val existing = currentPulse.conversations.firstOrNull { it.conversation.objectId == conversationId }
        val hydrated = (existing ?: GroupConversationPreview(
            conversation = WeaveObjectRef(conversationId, type = WeaveObjectType.Conversation),
            title = root.message.title ?: "Post",
            lastActivity = root.message.createdAt,
        )).copy(
            title = root.message.title ?: existing?.title ?: "Post",
            rootMessage = rootPreview,
            recentMessages = commentPreviews,
            replyCount = commentPreviews.size,
            lastActivity = maxOf(root.message.createdAt, commentPreviews.maxOfOrNull { it.createdAt } ?: 0L),
            recentUniqueAuthors = (listOf(root.message.authorId) + commentPreviews.map { it.authorId }).toSet().size,
            approximateParticipants = (listOf(root.message.authorId) + commentPreviews.map { it.authorId }).toSet().size,
        )
        val nextPulse = currentPulse.copy(
            conversations = (
                currentPulse.conversations.filterNot { it.conversation.objectId == conversationId } + hydrated
            ).sortedByDescending { it.lastActivity },
        )
        store.upsertPulse(nextPulse, selected.branchId)
        return detail
    }

    fun submitPost(
        groupId: String,
        conversationId: String,
        message: WeaveMessage,
        messageRef: WeaveObjectRef,
    ) {
        require(message.messageId == messageRef.objectId) { "Message ref does not identify this message" }
        val group = store.byId(groupId) ?: error("Unknown group")
        val event = GroupEvent(
            groupId = groupId,
            branchId = "*",
            kind = GroupEventKind.PostSubmitted,
            actorMainDht = ownMainDht(),
            createdAt = System.currentTimeMillis(),
            objectId = message.messageId,
            conversationId = conversationId,
            objectHash = weaveMessageDigest(message),
            metadata = mapOf("author_name" to message.authorName.take(80)),
        )
        deliverSubmission(group, GroupWireEnvelope(event, messageRef))
    }

    fun submitReport(groupId: String, targetId: String, targetHash: String, reason: String) {
        val event = GroupEvent(
            groupId = groupId,
            branchId = "*",
            kind = GroupEventKind.ReportSubmitted,
            actorMainDht = ownMainDht(),
            createdAt = System.currentTimeMillis(),
            objectId = targetId,
            objectHash = targetHash,
            reason = reason.take(600),
        )
        branches.sendPrivateAuthorityEvent(GroupWireEnvelope(event), authorities(groupId))
    }

    fun requestJoin(groupId: String, message: String = "") {
        val event = GroupEvent(
            groupId = groupId,
            branchId = "*",
            kind = GroupEventKind.JoinRequested,
            actorMainDht = ownMainDht(),
            createdAt = System.currentTimeMillis(),
            objectId = ownMainDht(),
            reason = message.take(600),
        )
        branches.sendPrivateAuthorityEvent(GroupWireEnvelope(event), authorities(groupId))
    }

    /**
     * If this device owns the selected branch it writes immediately. A secondary moderator sends
     * an authenticated action to that branch owner; the moderator never becomes a mirror.
     */
    fun moderatePost(
        groupId: String,
        postId: String,
        conversationId: String,
        postHash: String,
        state: GroupPostState,
        messageRef: WeaveObjectRef?,
        reason: String = "",
    ) {
        val selected = store.selectedBranch(groupId) ?: error("No moderation branch selected")
        val eventKind = when (state) {
            GroupPostState.Pending -> GroupEventKind.PostSubmitted
            GroupPostState.Visible -> GroupEventKind.PostAllowed
            GroupPostState.Removed -> GroupEventKind.PostRemoved
        }
        val action = GroupEvent(
            groupId = groupId,
            branchId = selected.branchId,
            kind = eventKind,
            actorMainDht = ownMainDht(),
            createdAt = System.currentTimeMillis(),
            objectId = postId,
            conversationId = conversationId,
            objectHash = postHash,
            reason = reason.take(600),
        )
        if (selected.ownerMainDht == ownMainDht()) {
            val header = branches.readHeader(selected.branchRoot) ?: error("Branch unavailable")
            branches.applyDecision(
                header, postId, conversationId, postHash, state, messageRef, ownMainDht(), reason
            )
            if (state == GroupPostState.Visible && messageRef != null) {
                addVisiblePostToPulse(header, action, messageRef)
            } else if (state == GroupPostState.Removed) {
                removePostFromPulse(header, conversationId, postId, reason, ownMainDht())
            }
            store.clearPendingForObject(postId)
        } else {
            branches.sendPrivateAuthorityEvent(
                GroupWireEnvelope(action, if (state == GroupPostState.Visible) messageRef else null),
                listOf(selected),
            )
        }
    }

    fun grantModerator(groupId: String, grant: GroupModeratorGrant): GroupBranchHeader {
        val selected = store.selectedBranch(groupId) ?: error("No selected branch")
        require(selected.ownerMainDht == ownMainDht()) { "Only a branch owner can assign its moderators" }
        val header = branches.readHeader(selected.branchRoot) ?: error("Branch unavailable")
        val updated = branches.grantModerator(
            header,
            grant.copy(grantedAt = System.currentTimeMillis()),
        )
        store.rememberBranchHeader(updated)
        return updated
    }

    fun setPinned(groupId: String, postId: String, pinned: Boolean) {
        val selected = store.selectedBranch(groupId) ?: error("No selected branch")
        if (selected.ownerMainDht == ownMainDht()) {
            val header = branches.readHeader(selected.branchRoot) ?: error("Branch unavailable")
            val updated = branches.setPinned(header, postId, pinned, ownMainDht())
            store.rememberBranchHeader(updated)
            refreshBranch(updated.pointer())
        } else {
            branches.sendPrivateAuthorityEvent(
                GroupWireEnvelope(
                    GroupEvent(
                        groupId = groupId,
                        branchId = selected.branchId,
                        kind = if (pinned) GroupEventKind.PostPinned else GroupEventKind.PostUnpinned,
                        actorMainDht = ownMainDht(),
                        createdAt = System.currentTimeMillis(),
                        objectId = postId,
                    )
                ),
                listOf(selected),
            )
        }
    }

    fun setFeatured(groupId: String, slot: FeaturedSlot) {
        val selected = store.selectedBranch(groupId) ?: error("No selected branch")
        if (selected.ownerMainDht == ownMainDht()) {
            val header = branches.readHeader(selected.branchRoot) ?: error("Branch unavailable")
            val updated = branches.setFeatured(header, slot, ownMainDht())
            store.rememberBranchHeader(updated)
            store.byId(groupId)?.let { store.updateGroup(it.copy(featured = updated.featured)) }
        } else {
            branches.sendPrivateAuthorityEvent(
                GroupWireEnvelope(
                    GroupEvent(
                        groupId = groupId,
                        branchId = selected.branchId,
                        kind = GroupEventKind.FeaturedChanged,
                        actorMainDht = ownMainDht(),
                        createdAt = System.currentTimeMillis(),
                        objectId = slot.ref?.objectId.orEmpty(),
                        objectHash = groupSha256Hex(slot.toJson().toString().encodeToByteArray()),
                        metadata = mapOf("featured_json" to slot.toJson().toString()),
                    )
                ),
                listOf(selected),
            )
        }
    }

    /** Resolve one actionable moderation item from Group-mode Me. */
    fun resolveModerationTask(taskId: String, approve: Boolean) {
        val task = store.moderationTask(taskId) ?: return
        logger("groups: moderation decision task=$taskId group=${task.groupId} kind=${task.kind.name} approve=$approve")
        val pending = store.pendingByEvent(taskId)
        when (task.kind) {
            GroupModerationKind.QuarantinedPost -> {
                val event = pending?.event ?: return
                val ownedPointer = store.branchesFor(task.groupId)
                    .firstOrNull { it.ownerMainDht == ownMainDht() } ?: return
                val header = branches.readHeader(ownedPointer.branchRoot) ?: return
                val state = if (approve) GroupPostState.Visible else GroupPostState.Removed
                branches.applyDecision(
                    header = header,
                    postId = event.objectId,
                    conversationId = event.conversationId,
                    postHash = event.objectHash,
                    state = state,
                    messageRef = if (approve) pending.contentRef else null,
                    actorMainDht = ownMainDht(),
                    reason = if (approve) "" else "Rejected during moderation",
                )
                if (approve) {
                    pending.contentRef?.let { addVisiblePostToPulse(header, event, it) }
                } else {
                    removePostFromPulse(header, event.conversationId, event.objectId, "Rejected during moderation", ownMainDht())
                }
                markBranchStateV2(
                    taskId,
                    ownedPointer.branchId,
                    if (approve) GroupBranchEventStateV2.Accepted else GroupBranchEventStateV2.Rejected,
                )
                store.clearPendingForObject(event.objectId)
                store.decide(taskId, if (approve) GroupModerationState.Approved else GroupModerationState.Rejected)
            }

            GroupModerationKind.JoinRequest -> {
                val requester = task.reporterId.ifBlank { pending?.event?.objectId.orEmpty() }
                if (requester.isBlank()) return
                val group = store.byId(task.groupId) ?: return
                // Private/member-only admission also has to deliver group keys. Keep that as a
                // separate invitation/key-distribution step rather than pretending a boolean
                // approval grants readable access.
                if (group.policy.visibility == GroupVisibility.MembersOnly && approve) return
                val ownedPointer = store.branchesFor(task.groupId)
                    .firstOrNull { it.ownerMainDht == ownMainDht() }
                val decision = GroupEvent(
                    groupId = task.groupId,
                    branchId = ownedPointer?.branchId ?: GroupBranchNetwork.originalBranchId(task.groupId),
                    kind = if (approve) GroupEventKind.JoinApproved else GroupEventKind.JoinRejected,
                    actorMainDht = ownMainDht(),
                    createdAt = System.currentTimeMillis(),
                    objectId = requester,
                    metadata = mapOf("request_id" to taskId),
                )
                if (ownedPointer != null && ownedPointer.branchRoot.isNotBlank()) {
                    branches.readHeader(ownedPointer.branchRoot)?.let { branches.recordObservedEvent(it, decision) }
                }
                client.sendMessage(requester, GroupWireEnvelope(decision).toBytes())
                store.decide(taskId, if (approve) GroupModerationState.Approved else GroupModerationState.Rejected)
                store.clearPending(taskId)
            }

            GroupModerationKind.Report,
            GroupModerationKind.Appeal -> {
                // Reviewing a report is deliberately separate from deleting the referenced post.
                // The moderator may then remove/keep it on their own branch.
                store.decide(taskId, if (approve) GroupModerationState.Approved else GroupModerationState.Rejected)
                store.clearPending(taskId)
            }
        }
    }

    /**
     * Direct + private mailbox handler. The daemon tells us sourceMainDht; the payload's actor is
     * not allowed to impersonate a different source for authority actions.
     */
    fun receivePrivate(envelope: GroupWireEnvelope, sourceMainDht: String) {
        val event = envelope.event
        if (sourceMainDht.isBlank()) return
        logger("groups: private intake kind=${event.kind.name} group=${event.groupId} source=$sourceMainDht target=${envelope.targetBranchOwner.ifBlank { "*" }}")
        // Initial submissions are authenticated by the daemon. Creator/claimer peers may relay the
        // same original event without rewriting its actor/eventId; only known authorities get that
        // exception, which preserves cross-path deduplication.
        val actorMatchesSource = event.actorMainDht.isBlank() || event.actorMainDht == sourceMainDht
        if (!actorMatchesSource && !isKnownAuthoritySender(event.groupId, sourceMainDht)) return
        if (isKnownAuthoritySender(event.groupId, sourceMainDht)) {
            observeAuthorityPeerLiveV2(sourceMainDht, "PrivateAuthorityEvent")
        }
        if (!store.markEventSeen(event.eventId)) return

        if (event.kind == GroupEventKind.JoinApproved || event.kind == GroupEventKind.JoinRejected) {
            if (!isKnownAuthoritySender(event.groupId, sourceMainDht)) return
            if (event.objectId != ownMainDht()) return
            store.setJoined(event.groupId, event.kind == GroupEventKind.JoinApproved)
            store.clearPendingForObject(ownMainDht())
            return
        }

        if (event.kind == GroupEventKind.ClaimCreated) {
            val branchRoot = event.metadata["branch_root"].orEmpty()
            val creatorRoot = event.metadata["creator_root"].orEmpty()
            if (branchRoot.isNotBlank() && creatorRoot.isNotBlank()) {
                branches.readHeader(branchRoot)?.let { claim ->
                    if (claim.kind == GroupBranchKind.Claim &&
                        claim.ownerMainDht == sourceMainDht &&
                        claim.creatorRoot == creatorRoot) {
                        store.rememberBranch(claim.pointer(), selectIfNone = false)
                        store.branchesFor(event.groupId)
                            .filter { it.ownerMainDht == ownMainDht() && it.branchId != claim.branchId }
                            .forEach { ownedPointer ->
                                branches.readHeader(ownedPointer.branchRoot)?.let { ownedHeader ->
                                    branches.observeBranch(ownedHeader, claim.pointer())
                                }
                            }
                    }
                }
            }
        }

        // A secondary moderator action addressed to a branch owner.
        val target = store.branchesFor(event.groupId).firstOrNull {
            it.branchId == event.branchId && it.ownerMainDht == ownMainDht()
        }
        if (target != null && event.kind in setOf(
                GroupEventKind.PostAllowed, GroupEventKind.PostRemoved, GroupEventKind.PostRestored
            )) {
            val header = branches.readHeader(target.branchRoot) ?: return
            val grant = header.moderators.firstOrNull { it.moderatorMainDht == sourceMainDht } ?: return
            if (!grant.canModeratePosts) return
            val state = if (event.kind == GroupEventKind.PostRemoved) GroupPostState.Removed else GroupPostState.Visible
            branches.applyDecision(
                header = header,
                postId = event.objectId,
                conversationId = event.conversationId,
                postHash = event.objectHash,
                state = state,
                messageRef = if (state == GroupPostState.Visible) envelope.contentRef else null,
                actorMainDht = sourceMainDht,
                reason = event.reason,
            )
            if (state == GroupPostState.Visible && envelope.contentRef != null) {
                addVisiblePostToPulse(header, event, envelope.contentRef)
            } else if (state == GroupPostState.Removed) {
                removePostFromPulse(header, event.conversationId, event.objectId, event.reason, sourceMainDht)
            }
            store.clearPendingForObject(event.objectId)
            return
        }

        if (target != null && event.kind == GroupEventKind.MemberBanned) {
            val header = branches.readHeader(target.branchRoot) ?: return
            val grant = header.moderators.firstOrNull { it.moderatorMainDht == sourceMainDht } ?: return
            if (!grant.canModeratePosts) return
            branches.banAuthor(header, event.objectId, sourceMainDht)
            return
        }

        if (target != null && event.kind in setOf(GroupEventKind.PostPinned, GroupEventKind.PostUnpinned)) {
            val header = branches.readHeader(target.branchRoot) ?: return
            val grant = header.moderators.firstOrNull { it.moderatorMainDht == sourceMainDht } ?: return
            if (!grant.canPinPosts) return
            val updated = branches.setPinned(
                header = header,
                postId = event.objectId,
                pinned = event.kind == GroupEventKind.PostPinned,
                actorMainDht = sourceMainDht,
            )
            store.rememberBranchHeader(updated)
            return
        }

        if (target != null && event.kind == GroupEventKind.FeaturedChanged) {
            val header = branches.readHeader(target.branchRoot) ?: return
            val grant = header.moderators.firstOrNull { it.moderatorMainDht == sourceMainDht } ?: return
            if (!grant.canEditFeatured) return
            val json = event.metadata["featured_json"].orEmpty()
            val slot = runCatching { FeaturedSlot.fromJson(JSONObject(json)) }.getOrNull() ?: return
            val updated = branches.setFeatured(header, slot, sourceMainDht)
            store.rememberBranchHeader(updated)
            store.byId(event.groupId)?.let { store.updateGroup(it.copy(featured = slot)) }
            return
        }

        branches.receivePrivate(envelope, sourceMainDht)

        // Creator/claimers relay submissions/branch observations to their compatriots.
        if (event.kind in setOf(GroupEventKind.PostSubmitted, GroupEventKind.ConversationCreated)) {
            val mine = store.branchesFor(event.groupId).filter { it.ownerMainDht == ownMainDht() }
            if (mine.isNotEmpty()) {
                val others = authorities(event.groupId).filter {
                    it.ownerMainDht != ownMainDht() && it.ownerMainDht != sourceMainDht
                }
                if (others.isNotEmpty()) branches.sendPrivateAuthorityEvent(envelope, others)
                mine.forEach { maybeAutoAccept(it, envelope) }
            }
        }
    }

    /** Receive one canonical Groups-v2 event from an authenticated daemon path. */
    fun receiveCanonicalEventV2(
        packet: GroupEventTransportPacketV2,
        sourceMainDht: String,
        transport: GroupEventTransportV2,
        publicExpiresAt: Long = 0L,
    ) {
        val event = packet.event
        if (sourceMainDht.isBlank() || sourceMainDht != event.authorMainDht) {
            logger("[groups-v2] TRANSPORT_SOURCE_MISMATCH event=${event.eventId.take(8)} transport=${transport.name} source=${short(sourceMainDht)} claimed_author=${short(event.authorMainDht)}")
            return
        }
        if (packet.targetOwnerMainDht.isNotBlank() && packet.targetOwnerMainDht != ownMainDht()) {
            logger("[groups-v2] TRANSPORT_TARGET_MISMATCH event=${event.eventId.take(8)} target=${short(packet.targetOwnerMainDht)} local=${short(ownMainDht())}")
            return
        }
        val structuralError = event.structuralError()
        if (structuralError != null) {
            logger("[groups-v2] EVENT_PROCESS_REJECT event=${event.eventId.take(8)} transport=${transport.name} reason=structure-$structuralError")
            return
        }
        val abuseAction = if (transport == GroupEventTransportV2.PublicWitness) GroupAbuseActionV2.PublicWitness else GroupAbuseActionV2.AuthenticatedEvent
        if (!abuseGuardV2.allow(abuseAction, sourceMainDht, event.groupId)) return

        val ownedBranches = store.branchesFor(event.groupId)
            .filter { it.ownerMainDht == ownMainDht() }
            .map { it.branchId }
        val stored = eventStoreV2?.ingest(
            event = event,
            transport = transport,
            sourcePeer = sourceMainDht,
            branchIdsForLocalState = ownedBranches,
        ) ?: return
        if (transport == GroupEventTransportV2.Direct) observeAuthorityPeerLiveV2(sourceMainDht, "DirectGroupEvent")
        recordCrossTransportMatchEvidenceV2(stored)
        recordAuthenticatedEquivocationEvidenceV2(stored)
        mirrorBranchStatesV2(stored)
        if (stored.validation != GroupEventValidationV2.Valid) {
            logger("[groups-v2] EVENT_PROCESS_REJECT event=${event.eventId.take(8)} validation=${stored.validation.name}")
            return
        }

        // Once a spectator has already hash-verified this payload, later witness duplicates only
        // need to strengthen delivery evidence. Likewise, an authority whose relevant branch
        // decision is already terminal does not need to re-read the author's DHT for every
        // mailbox/public duplicate. Pending branches still re-fetch so a previously unavailable
        // payload can recover naturally on the next transport arrival.
        val allOwnedBranchesTerminal = ownedBranches.isNotEmpty() && ownedBranches.all { branchId ->
            stored.branchStates[branchId] in setOf(
                GroupBranchEventStateV2.Accepted,
                GroupBranchEventStateV2.Rejected,
                GroupBranchEventStateV2.Ignored,
            )
        }
        if (stored.contentState == GroupEventContentStateV2.Valid &&
            (ownedBranches.isEmpty() || allOwnedBranchesTerminal)
        ) {
            logger("[groups-v2] CONTENT_REUSE event=${event.eventId.take(8)} transport=${transport.name} branches_terminal=$allOwnedBranchesTerminal")
            if (ownedBranches.isEmpty()) {
                logger("[groups-v2] EVENT_STORED_SPECTATOR event=${event.eventId.take(8)} group=${short(event.groupId)} transport=${transport.name}")
            }
            return
        }

        enqueueContentValidationV2(
            eventId = event.eventId,
            transport = transport,
            sourceMainDht = sourceMainDht,
            publicExpiresAt = publicExpiresAt,
            delayMs = 0L,
            reason = "arrival",
        )
    }

    /**
     * Resume unresolved Groups-v2 content after Weave/daemon restart. The signed event is already
     * durable in PrivateVault, so startup recovery only needs to re-fetch its author-owned DHT.
     */
    fun recoverPendingContentV2() {
        val storeV2 = eventStoreV2 ?: return
        if (closed.get()) return
        val now = System.currentTimeMillis()
        var candidates = 0
        storeV2.allEvents().forEach { stored ->
            if (stored.validation != GroupEventValidationV2.Valid) return@forEach
            if (stored.event.expiresAt in 1 until now) return@forEach
            val ownedBranches = store.branchesFor(stored.event.groupId)
                .filter { it.ownerMainDht == sessionMainDht }
                .map { it.branchId }
            val unresolvedContent = stored.contentState in setOf(
                GroupEventContentStateV2.Unchecked,
                GroupEventContentStateV2.Unavailable,
            )
            val unresolvedOwnedBranch = ownedBranches.any { branchId ->
                stored.branchStates[branchId] !in setOf(
                    GroupBranchEventStateV2.Accepted,
                    GroupBranchEventStateV2.Rejected,
                    GroupBranchEventStateV2.Ignored,
                )
            }
            if (!unresolvedContent && !unresolvedOwnedBranch) return@forEach
            val transport = recoveryTransportV2(stored)
            val delay = (stored.contentNextRetryAt - now).coerceAtLeast(0L)
            enqueueContentValidationV2(
                eventId = stored.event.eventId,
                transport = transport,
                sourceMainDht = stored.event.authorMainDht,
                publicExpiresAt = 0L,
                delayMs = delay,
                reason = "startup",
            )
            candidates++
        }
        logger("[groups-v2] CONTENT_RECOVERY_SCAN candidates=$candidates session=${short(sessionMainDht)}")
    }

    private fun recoveryTransportV2(stored: StoredGroupEventV2): GroupEventTransportV2 = when {
        stored.evidence.containsKey(GroupEventTransportV2.PrivateMailbox) -> GroupEventTransportV2.PrivateMailbox
        stored.evidence.containsKey(GroupEventTransportV2.Direct) -> GroupEventTransportV2.Direct
        stored.evidence.containsKey(GroupEventTransportV2.PublicWitness) -> GroupEventTransportV2.PublicWitness
        else -> stored.evidence.keys.firstOrNull() ?: GroupEventTransportV2.LegacyBridge
    }

    private fun enqueueContentValidationV2(
        eventId: String,
        transport: GroupEventTransportV2,
        sourceMainDht: String,
        publicExpiresAt: Long,
        delayMs: Long,
        reason: String,
    ) {
        if (closed.get()) return
        if (!contentValidationQueued.add(eventId)) {
            logger("[groups-v2] CONTENT_FETCH_ALREADY_QUEUED event=${eventId.take(8)} transport=${transport.name}")
            return
        }
        logger("[groups-v2] CONTENT_FETCH_QUEUED event=${eventId.take(8)} transport=${transport.name} reason=$reason delay=${delayMs}ms")
        contentRecoveryScheduler.schedule({
            runContentValidationV2(eventId, transport, sourceMainDht, publicExpiresAt)
        }, delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    }

    private fun runContentValidationV2(
        eventId: String,
        transport: GroupEventTransportV2,
        sourceMainDht: String,
        publicExpiresAt: Long,
    ) {
        var retryDelay: Long? = null
        try {
            if (closed.get() || ownMainDht() != sessionMainDht) return
            val storeV2 = eventStoreV2 ?: return
            val stored = storeV2.get(eventId) ?: return
            if (stored.validation != GroupEventValidationV2.Valid) return
            if (stored.event.expiresAt in 1 until System.currentTimeMillis()) return

            val ownedBranches = store.branchesFor(stored.event.groupId)
                .filter { it.ownerMainDht == sessionMainDht }
                .map { it.branchId }
            val allOwnedBranchesTerminal = ownedBranches.isNotEmpty() && ownedBranches.all { branchId ->
                stored.branchStates[branchId] in setOf(
                    GroupBranchEventStateV2.Accepted,
                    GroupBranchEventStateV2.Rejected,
                    GroupBranchEventStateV2.Ignored,
                )
            }
            if (stored.contentState == GroupEventContentStateV2.Valid &&
                (ownedBranches.isEmpty() || allOwnedBranchesTerminal)
            ) {
                logger("[groups-v2] CONTENT_REUSE event=${eventId.take(8)} transport=${transport.name} branches_terminal=$allOwnedBranchesTerminal")
                if (ownedBranches.isEmpty()) {
                    logger("[groups-v2] EVENT_STORED_SPECTATOR event=${eventId.take(8)} group=${short(stored.event.groupId)} transport=${transport.name}")
                }
                return
            }

            val priorRetryCount = stored.contentRetryCount
            val fetchStarted = System.currentTimeMillis()
            logger("[groups-v2] CONTENT_FETCH_BEGIN event=${eventId.take(8)} record=${short(stored.event.payload.recordKey)} subkey=${stored.event.payload.subkey} transport=${transport.name} timeout=${CONTENT_FETCH_TIMEOUT_MS}ms retry=$priorRetryCount")
            val future = contentFetchPool.submit<WeaveMessage?> {
                content.fetch(stored.event.payload.toWeaveRef())
            }
            val message = try {
                future.get(CONTENT_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                logger("[groups-v2] CONTENT_FETCH_CANCELLED event=${eventId.take(8)} transport=${transport.name} session=${short(sessionMainDht)}")
                return
            } catch (_: TimeoutException) {
                future.cancel(true)
                logger("[groups-v2] CONTENT_FETCH_TIMEOUT event=${eventId.take(8)} transport=${transport.name} timeout=${CONTENT_FETCH_TIMEOUT_MS}ms")
                retryDelay = storeV2.markContentUnavailable(eventId, fetchStarted, "timeout")
                null
            } catch (t: Throwable) {
                future.cancel(true)
                logger("[groups-v2] CONTENT_FETCH_FAILED event=${eventId.take(8)} transport=${transport.name} error=${t.message.orEmpty().take(160)}")
                retryDelay = storeV2.markContentUnavailable(eventId, fetchStarted, "fetch-error")
                null
            }
            if (retryDelay != null) return
            if (closed.get() || ownMainDht() != sessionMainDht) return
            if (message == null) {
                retryDelay = storeV2.markContentUnavailable(eventId, fetchStarted, "not-found")
                logger("[groups-v2] EVENT_PROCESS_DEFER event=${eventId.take(8)} content=Unavailable")
                return
            }
            val contentState = storeV2.verifyContentMessage(eventId, message, fetchStarted)
            if (contentState != GroupEventContentStateV2.Valid) {
                logger("[groups-v2] EVENT_PROCESS_DEFER event=${eventId.take(8)} content=${contentState.name}")
                return
            }
            if (priorRetryCount > 0) {
                logger("[groups-v2] CONTENT_RECOVERY_SUCCESS event=${eventId.take(8)} attempts=$priorRetryCount transport=${transport.name}")
            }
            if (transport == GroupEventTransportV2.CustodyRecovery) {
                recordCustodyContentVerifiedEvidenceV2(stored)
            }
            processCanonicalForOwnedBranches(stored, message, transport, sourceMainDht, publicExpiresAt)
        } finally {
            contentValidationQueued.remove(eventId)
            val delay = retryDelay
            if (delay != null && !closed.get() && ownMainDht() == sessionMainDht) {
                enqueueContentValidationV2(
                    eventId = eventId,
                    transport = transport,
                    sourceMainDht = sourceMainDht,
                    publicExpiresAt = publicExpiresAt,
                    delayMs = delay,
                    reason = "retry",
                )
            }
        }
    }

    private fun observeAuthorityPeerLiveV2(peerMainDht: String, signal: String) {
        if (peerMainDht.isBlank()) return
        val ownsKnownBranch = store.all().any { group ->
            store.branchesFor(group.groupId).any { it.ownerMainDht == peerMainDht }
        }
        if (ownsKnownBranch) trustStoreV2?.observePeerLive(peerMainDht, signal)
    }

    private fun recordCrossTransportMatchEvidenceV2(stored: StoredGroupEventV2) {
        val sourceBoundTransports = setOf(
            GroupEventTransportV2.Direct,
            GroupEventTransportV2.PrivateMailbox,
            GroupEventTransportV2.PublicWitness,
        )
        val matchingPaths = stored.evidence.values.count { evidence ->
            evidence.transport in sourceBoundTransports && stored.event.authorMainDht in evidence.sources
        }
        if (matchingPaths < 2) return
        trustStoreV2?.recordEvidence(
            peerMainDht = stored.event.authorMainDht,
            kind = GroupReputationEvidenceKindV2.CrossTransportCanonicalMatch,
            evidenceKey = stored.event.eventId,
            groupId = stored.event.groupId,
            eventId = stored.event.eventId,
            detail = "$matchingPaths source-bound transports carried identical canonical bytes",
        )
    }

    private fun recordAuthenticatedEquivocationEvidenceV2(stored: StoredGroupEventV2) {
        val conflicts = stored.conflicts.filter { it.classification == GroupEventConflictClassV2.AuthenticatedEquivocation }
        if (conflicts.isEmpty() || stored.event.authorMainDht == sessionMainDht) return
        val sourceBoundTransports = setOf(
            GroupEventTransportV2.Direct,
            GroupEventTransportV2.PrivateMailbox,
            GroupEventTransportV2.PublicWitness,
        )
        val canonicalCameFromClaimedAuthor = stored.evidence.values.any { evidence ->
            evidence.transport in sourceBoundTransports && stored.event.authorMainDht in evidence.sources
        }
        val conflictingCameFromClaimedAuthor = conflicts.any { conflict ->
            conflict.transport in sourceBoundTransports && conflict.sourcePeer == stored.event.authorMainDht
        }
        val networkProven = canonicalCameFromClaimedAuthor && conflictingCameFromClaimedAuthor
        if (!networkProven && stored.authorBinding != GroupEventAuthorBindingV2.RemoteProfileBound) {
            logger("[groups-v2] REPUTATION_WITHHELD event=${stored.event.eventId.take(8)} reason=author-signing-key-unbound-and-source-unproven")
            return
        }
        trustStoreV2?.recordEvidence(
            peerMainDht = stored.event.authorMainDht,
            kind = GroupReputationEvidenceKindV2.AuthenticatedEventEquivocation,
            evidenceKey = stored.event.eventId,
            groupId = stored.event.groupId,
            eventId = stored.event.eventId,
            detail = if (networkProven)
                "conflicting canonical bodies arrived from the same authenticated Main-DHT source"
            else
                "same event id validated under a profile-bound signing key with conflicting canonical bodies",
        )
    }

    private fun recordCustodyContentVerifiedEvidenceV2(stored: StoredGroupEventV2) {
        val custodians = stored.evidence[GroupEventTransportV2.CustodyRecovery]?.sources.orEmpty()
        custodians.forEach { custodian ->
            trustStoreV2?.recordEvidence(
                peerMainDht = custodian,
                kind = GroupReputationEvidenceKindV2.RecoveryContentVerified,
                evidenceKey = stored.event.eventId,
                groupId = stored.event.groupId,
                eventId = stored.event.eventId,
            )
            val honored = custodyStoreV2?.receiptsForEvent(stored.event.eventId)
                ?.any { it.verified && it.custodianMainDht == custodian && it.canonicalDigestHex.equals(stored.event.canonicalDigestHex(), true) } == true
            if (honored) {
                trustStoreV2?.recordEvidence(
                    peerMainDht = custodian,
                    kind = GroupReputationEvidenceKindV2.CustodyReceiptHonored,
                    evidenceKey = stored.event.eventId,
                    groupId = stored.event.groupId,
                    eventId = stored.event.eventId,
                )
            }
        }
    }

    /** Stop recovery workers before this daemon/account session is discarded. */
    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        contentValidationQueued.clear()
        contentRecoveryScheduler.shutdownNow()
        contentFetchPool.shutdownNow()
        custodyPool.shutdownNow()
        outboundRecoveryRequests.clear()
        logger("[groups-v2] CONTENT_RECOVERY_STOP session=${short(sessionMainDht)}")
    }

    private fun processCanonicalForOwnedBranches(
        stored: StoredGroupEventV2,
        message: WeaveMessage,
        transport: GroupEventTransportV2,
        sourceMainDht: String,
        publicExpiresAt: Long = 0L,
    ) {
        val event = stored.event
        val legacy = GroupEvent(
            eventId = event.eventId,
            groupId = event.groupId,
            branchId = "*",
            kind = GroupEventKind.PostSubmitted,
            actorMainDht = event.authorMainDht,
            createdAt = event.createdAt,
            objectId = event.payload.objectId,
            conversationId = message.container.objectId,
            objectHash = weaveMessageDigest(message),
            metadata = buildMap {
                put("author_name", message.authorName)
                message.title?.let { put("post_title", it) }
                if (event.eventType == GroupEventTypeV2.CommentSubmitted) put("reply_kind", "comment")
                put("groups_v2_event_id", event.eventId)
            },
        )
        val envelope = GroupWireEnvelope(legacy, event.payload.toWeaveRef())
        if (transport == GroupEventTransportV2.PublicWitness) {
            store.rememberPublicPending(legacy, envelope.contentRef, publicExpiresAt.takeIf { it > 0L } ?: event.expiresAt)
        } else if (transport != GroupEventTransportV2.LocalCreated) {
            store.rememberPendingEnvelope(legacy, envelope.contentRef, sourceMainDht)
        }

        val mine = store.branchesFor(event.groupId).filter { it.ownerMainDht == ownMainDht() }
        if (mine.isEmpty()) {
            logger("[groups-v2] EVENT_STORED_SPECTATOR event=${event.eventId.take(8)} group=${short(event.groupId)} transport=${transport.name}")
            return
        }
        mine.forEach { maybeAutoAccept(it, envelope, event.eventId) }
    }

    private fun deliverCanonicalSubmissionV2(group: GroupRecord, event: SignedGroupEventV2) {
        val targets = authorities(group.groupId)
        scheduleCustodyCopiesV2(group, event, targets)
        if (targets.isEmpty()) {
            logger("[groups-v2] TRANSPORT_NO_AUTHORITY event=${event.eventId.take(8)} group=${short(group.groupId)} preserved_locally=true")
            return
        }
        val key = if (group.policy.visibility == GroupVisibility.MembersOnly) store.privateIntakeKey(group.groupId) else null
        branches.sendCanonicalSubmissionV2(event, targets, key)
    }


    /**
     * Phase 3A custody is intentionally sent over authenticated direct/mailbox messages, not the
     * 1024-byte ServiceRequest witness channel. Ordinary membership is private, so senders sample
     * relevant app peers; receivers accept custody only when their own local group state says they
     * joined the group. Unlisted/member-only groups are not sampled until Weave has a
     * membership-addressable peer set, avoiding disclosure of a non-public group ID to unrelated
     * app peers.
     */
    private fun scheduleCustodyCopiesV2(
        group: GroupRecord,
        event: SignedGroupEventV2,
        authorities: List<GroupBranchPointer>,
    ) {
        val custody = custodyStoreV2 ?: return
        if (group.policy.visibility != GroupVisibility.Public) {
            logger("[groups-v2] CUSTODY_SKIP event=${event.eventId.take(8)} group=${short(group.groupId)} reason=nonpublic-membership-not-addressable")
            return
        }
        val authorityIds = authorities.map { it.ownerMainDht }.toSet()
        val candidates = custodyCandidates()
            .asSequence()
            .filter { it.isNotBlank() && it != sessionMainDht && it !in authorityIds }
            .filter { trustStoreV2?.protocolEligible(it) != false }
            .distinct()
            .sortedWith(
                compareByDescending<String> { trustStoreV2?.protocolPreference(it) ?: 0 }
                    .thenBy { sha256Hex("${event.eventId}|$it".encodeToByteArray()) }
            )
            .take(CUSTODY_TARGETS_PER_EVENT)
            .toList()
        logger("[groups-v2] CUSTODY_SELECTED event=${event.eventId.take(8)} candidates=${candidates.size}")
        candidates.forEach { peer ->
            queueCustodyWork("store-${event.eventId.take(8)}") {
                if (closed.get()) return@queueCustodyWork
                runCatching {
                    val request = signCustodyStoreRequestV2(
                        event = event,
                        requesterMainDht = sessionMainDht,
                        requestedRetainUntil = minOf(event.expiresAt, System.currentTimeMillis() + GROUP_EVENT_RETENTION_MS_V2),
                    )
                    val bytes = GroupCustodyWireCodecV2.encode(request)
                    client.sendMessage(
                        recipientMainDht = peer,
                        payload = bytes,
                        expiresAtSeconds = (event.expiresAt / 1000L).takeIf { it > 0L },
                        preferDirect = true,
                    )
                    logger("[groups-v2] CUSTODY_STORE_SENT event=${event.eventId.take(8)} target=${short(peer)} bytes=${bytes.size}")
                }.onFailure {
                    logger("[groups-v2] CUSTODY_STORE_SEND_FAILED event=${event.eventId.take(8)} target=${short(peer)} error=${it.message.orEmpty().take(180)}")
                }
            }
        }
        // Touch the store so its encrypted state is loaded/cleaned in the same account session.
        custody.cleanup()
    }

    /** Request recent retained refs for every branch owned by this account. Called after peer refresh. */
    fun recoverCustodyV2() {
        val custody = custodyStoreV2 ?: return
        if (closed.get()) return
        val now = System.currentTimeMillis()
        val ownedByGroup = store.all()
            .asSequence()
            .filter { group ->
                if (group.policy.visibility == GroupVisibility.Public) {
                    true
                } else {
                    val ownsBranch = store.branchesFor(group.groupId).any { it.ownerMainDht == sessionMainDht }
                    if (ownsBranch) {
                        logger("[groups-v2] CUSTODY_SKIP group=${short(group.groupId)} reason=nonpublic-recovery-not-addressable")
                    }
                    false
                }
            }
            .flatMap { group -> store.branchesFor(group.groupId).asSequence() }
            .filter { it.ownerMainDht == sessionMainDht }
            .groupBy { it.groupId }
        if (ownedByGroup.isEmpty()) {
            logger("[groups-v2] RECOVERY_BEGIN groups=0 peers=0")
            return
        }
        val peers = custodyCandidates()
            .filter { it.isNotBlank() && it != sessionMainDht }
            .filter { trustStoreV2?.protocolEligible(it) != false }
            .distinct()
        if (peers.isEmpty()) return
        val previousRecovery = lastCustodyRecoveryStartedAt.get()
        if (previousRecovery > 0L && now - previousRecovery < RECOVERY_REFRESH_INTERVAL_MS) return
        if (!lastCustodyRecoveryStartedAt.compareAndSet(previousRecovery, now)) return
        pruneOutboundRecoveryRequestsV2(now)
        if (outboundRecoveryRequests.size >= MAX_OUTBOUND_RECOVERY_REQUESTS) {
            logger("[groups-v2] RECOVERY_SKIP reason=outbound-cap active=${outboundRecoveryRequests.size}")
            return
        }
        val orderedGroups = ownedByGroup.entries
            .sortedBy { entry -> entry.value.minOfOrNull { custody.recoveryCursor(entry.key, it.branchId)?.lastRecoveryStartedAt ?: 0L } ?: 0L }
            .take(MAX_RECOVERY_GROUPS_PER_SWEEP)
        logger("[groups-v2] RECOVERY_BEGIN groups=${orderedGroups.size}/${ownedByGroup.size} peers=${peers.size}")
        var requestsScheduled = 0
        orderedGroups.forEach { (groupId, branchesOwned) ->
            // Phase 3A deliberately re-asks the bounded seven-day window. A branch-global
            // high-water mark is unsafe while different custodians may hold disjoint subsets: a
            // newly discovered custodian could still hold an older event. Per-request cursors are
            // used for pagination; EventStore dedupe makes re-fetching safe.
            val since = (now - GROUP_EVENT_RETENTION_MS_V2).coerceAtLeast(0L)
            branchesOwned.forEach { custody.markRecoveryStarted(groupId, it.branchId, now) }
            val receiptCustodians = custody.verifiedCustodiansForGroup(groupId, now)
            val selectedPeers = peers.sortedWith(
                compareByDescending<String> { it in receiptCustodians }
                    .thenByDescending { trustStoreV2?.protocolPreference(it) ?: 0 }
                    .thenBy { sha256Hex("$groupId|$it".encodeToByteArray()) }
            ).take(RECOVERY_CUSTODIANS_PER_GROUP)
            for (peer in selectedPeers) {
                if (requestsScheduled >= MAX_RECOVERY_REQUESTS_PER_SWEEP) break
                requestsScheduled++
                sendCustodyRecoveryRequestV2(
                    groupId = groupId,
                    custodianMainDht = peer,
                    sinceCreatedAt = since,
                    untilCreatedAt = now,
                    cursor = "",
                    branchIds = branchesOwned.map { it.branchId },
                )
            }
        }
    }

    /** Returns true when [message] belonged to the custody protocol, even if validation rejected it. */
    fun receiveCustodyWireV2(message: GroupCustodyWireMessageV2, sourceMainDht: String): Boolean {
        if (closed.get()) return true
        val (action, groupId) = when (message) {
            is CustodyStoreRequestV2 -> GroupAbuseActionV2.CustodyStoreRequest to message.groupId
            is CustodyReceiptV2 -> GroupAbuseActionV2.CustodyReceipt to message.groupId
            is CustodyRecoveryRequestV2 -> GroupAbuseActionV2.RecoveryRequest to message.groupId
            is CustodyRecoveryBatchV2 -> GroupAbuseActionV2.RecoveryBatch to message.groupId
        }
        if (!abuseGuardV2.allow(action, sourceMainDht, groupId)) return true
        observeAuthorityPeerLiveV2(sourceMainDht, "CustodyProtocol")
        when (message) {
            is CustodyStoreRequestV2 -> receiveCustodyStoreRequestV2(message, sourceMainDht)
            is CustodyReceiptV2 -> receiveCustodyReceiptV2(message, sourceMainDht)
            is CustodyRecoveryRequestV2 -> receiveCustodyRecoveryRequestV2(message, sourceMainDht)
            is CustodyRecoveryBatchV2 -> receiveCustodyRecoveryBatchV2(message, sourceMainDht)
        }
        return true
    }

    private fun receiveCustodyStoreRequestV2(request: CustodyStoreRequestV2, sourceMainDht: String) {
        val custody = custodyStoreV2 ?: return
        if (request.protocolVersion != 2 || sourceMainDht != request.requesterMainDht || request.groupId != request.event.groupId) {
            logger("[groups-v2] CUSTODY_REJECT event=${request.event.eventId.take(8)} reason=envelope-binding")
            return
        }
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(now - request.sentAt) > CUSTODY_REQUEST_FRESHNESS_MS) {
            logger("[groups-v2] CUSTODY_REJECT event=${request.event.eventId.take(8)} reason=stale")
            return
        }
        request.event.structuralError(now)?.let {
            logger("[groups-v2] CUSTODY_REJECT event=${request.event.eventId.take(8)} reason=structure-$it")
            return
        }
        if (!verifyCustodySignatureV2(request.signingPublicKeyHex, CustodyStoreRequestV2.SIGNING_DOMAIN, request.unsignedCanonicalBytes(), request.signatureHex)) {
            logger("[groups-v2] CUSTODY_REJECT event=${request.event.eventId.take(8)} reason=request-signature")
            return
        }
        val group = store.byId(request.groupId)
        val ownsBranch = store.branchesFor(request.groupId).any { it.ownerMainDht == sessionMainDht }
        val eligible = group != null && (group.joined || ownsBranch) &&
            (group.policy.visibility != GroupVisibility.MembersOnly || ownsBranch || store.privateIntakeKey(request.groupId) != null)
        if (!eligible) {
            logger("[groups-v2] CUSTODY_REJECT event=${request.event.eventId.take(8)} group=${short(request.groupId)} reason=not-local-member")
            return
        }
        val validation = eventStoreV2?.validateStandalone(request.event) ?: GroupEventValidationV2.Unvalidated
        if (validation != GroupEventValidationV2.Valid) {
            logger("[groups-v2] CUSTODY_REJECT event=${request.event.eventId.take(8)} reason=event-${validation.name}")
            return
        }
        val stored = custody.storeEvent(request.event, sourceMainDht, request.requestedRetainUntil) ?: return
        if (!request.receiptRequested) return
        val receipt = signCustodyReceiptV2(stored)
        val bytes = GroupCustodyWireCodecV2.encode(receipt)
        custody.markReceiptIssued(request.event.eventId)
        queueCustodyWork("receipt-${request.event.eventId.take(8)}") {
            runCatching {
                client.sendMessage(
                    recipientMainDht = sourceMainDht,
                    payload = bytes,
                    expiresAtSeconds = (stored.retainUntil / 1000L).takeIf { it > 0L },
                    preferDirect = true,
                )
                logger("[groups-v2] CUSTODY_RECEIPT_SENT event=${request.event.eventId.take(8)} target=${short(sourceMainDht)}")
            }.onFailure {
                logger("[groups-v2] CUSTODY_RECEIPT_SEND_FAILED event=${request.event.eventId.take(8)} target=${short(sourceMainDht)} error=${it.message.orEmpty().take(180)}")
            }
        }
    }

    private fun receiveCustodyReceiptV2(receipt: CustodyReceiptV2, sourceMainDht: String) {
        val custody = custodyStoreV2 ?: return
        if (receipt.protocolVersion != 2 || receipt.custodianMainDht != sourceMainDht) return
        val receiptNow = System.currentTimeMillis()
        if (receipt.storedAt <= 0L || receipt.storedAt > receiptNow + CUSTODY_CLOCK_SKEW_MS || receipt.retainUntil <= receipt.storedAt || receipt.retainUntil > receiptNow + GROUP_EVENT_RETENTION_MS_V2 + CUSTODY_CLOCK_SKEW_MS) {
            logger("[groups-v2] CUSTODY_RECEIPT_REJECT event=${receipt.eventId.take(8)} custodian=${short(sourceMainDht)} reason=timestamp-bounds")
            return
        }
        val valid = verifyCustodySignatureV2(
            receipt.signingPublicKeyHex,
            CustodyReceiptV2.SIGNING_DOMAIN,
            receipt.unsignedCanonicalBytes(),
            receipt.signatureHex,
        )
        if (!valid) {
            trustStoreV2?.recordEvidence(
                peerMainDht = sourceMainDht,
                kind = GroupReputationEvidenceKindV2.InvalidCustodyReceiptSignature,
                evidenceKey = receipt.eventId,
                groupId = receipt.groupId,
                eventId = receipt.eventId,
            )
            logger("[groups-v2] CUSTODY_RECEIPT_REJECT event=${receipt.eventId.take(8)} custodian=${short(sourceMainDht)} reason=signature")
            return
        }
        val local = eventStoreV2?.get(receipt.eventId)?.event
        if (local == null || local.groupId != receipt.groupId) {
            logger("[groups-v2] CUSTODY_RECEIPT_REJECT event=${receipt.eventId.take(8)} custodian=${short(sourceMainDht)} reason=unknown")
            return
        }
        if (!local.canonicalDigestHex().equals(receipt.canonicalDigestHex, true)) {
            trustStoreV2?.recordEvidence(
                peerMainDht = sourceMainDht,
                kind = GroupReputationEvidenceKindV2.CustodyReceiptDigestContradiction,
                evidenceKey = receipt.eventId,
                groupId = receipt.groupId,
                eventId = receipt.eventId,
                detail = "signed receipt digest disagrees with local canonical event",
            )
            logger("[groups-v2] CUSTODY_RECEIPT_REJECT event=${receipt.eventId.take(8)} custodian=${short(sourceMainDht)} reason=digest-contradiction")
            return
        }
        custody.rememberReceipt(receipt, GroupCustodyWireCodecV2.encode(receipt), verified = true)
        trustStoreV2?.recordEvidence(
            peerMainDht = sourceMainDht,
            kind = GroupReputationEvidenceKindV2.CustodyReceiptVerified,
            evidenceKey = receipt.eventId,
            groupId = receipt.groupId,
            eventId = receipt.eventId,
        )
    }

    private fun receiveCustodyRecoveryRequestV2(request: CustodyRecoveryRequestV2, sourceMainDht: String) {
        val custody = custodyStoreV2 ?: return
        if (request.protocolVersion != 2 || sourceMainDht != request.requesterMainDht) return
        if (!verifyCustodySignatureV2(request.signingPublicKeyHex, CustodyRecoveryRequestV2.SIGNING_DOMAIN, request.unsignedCanonicalBytes(), request.signatureHex)) {
            logger("[groups-v2] RECOVERY_REQUEST_REJECT group=${short(request.groupId)} source=${short(sourceMainDht)} reason=signature")
            return
        }
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(now - request.sentAt) > RECOVERY_REQUEST_FRESHNESS_MS) {
            logger("[groups-v2] RECOVERY_REQUEST_REJECT group=${short(request.groupId)} source=${short(sourceMainDht)} reason=stale")
            return
        }
        if (trustStoreV2?.protocolEligible(sourceMainDht) == false) {
            logger("[groups-v2] RECOVERY_REQUEST_REJECT group=${short(request.groupId)} source=${short(sourceMainDht)} reason=quarantined")
            return
        }
        if (request.sinceCreatedAt < 0L || request.untilCreatedAt < 0L ||
            (request.untilCreatedAt > 0L && request.sinceCreatedAt > request.untilCreatedAt) ||
            (request.untilCreatedAt > 0L && request.untilCreatedAt - request.sinceCreatedAt > GROUP_EVENT_RETENTION_MS_V2 + RECOVERY_WINDOW_GRACE_MS) ||
            request.cursor.encodeToByteArray().size > MAX_RECOVERY_CURSOR_BYTES
        ) {
            logger("[groups-v2] RECOVERY_REQUEST_REJECT group=${short(request.groupId)} source=${short(sourceMainDht)} reason=bounds")
            return
        }
        val localGroup = store.byId(request.groupId) ?: return
        val localCanServe = localGroup.joined || store.branchesFor(request.groupId).any { it.ownerMainDht == sessionMainDht }
        val requesterIsAuthority = localGroup.ownerId == sourceMainDht || store.branchesFor(request.groupId).any { it.ownerMainDht == sourceMainDht }
        if (!localCanServe || !requesterIsAuthority) {
            logger("[groups-v2] RECOVERY_REQUEST_REJECT group=${short(request.groupId)} source=${short(sourceMainDht)} reason=membership-or-authority")
            return
        }
        if (!custody.markRecoveryRequestSeen(request.requestId, sourceMainDht, now)) {
            logger("[groups-v2] RECOVERY_DUPLICATE request=${request.requestId.take(8)} source=${short(sourceMainDht)}")
            return
        }
        val since = request.sinceCreatedAt.coerceAtLeast(now - GROUP_EVENT_RETENTION_MS_V2)
        val until = request.untilCreatedAt.takeIf { it > 0L }?.coerceAtMost(now) ?: now
        val page = custody.recoveryPage(
            groupId = request.groupId,
            sinceCreatedAt = since,
            untilCreatedAt = until,
            cursor = request.cursor,
            maxEvents = request.maxEvents,
            now = now,
        )
        val batch = signSizedRecoveryBatchV2(request, page)
        val bytes = GroupCustodyWireCodecV2.encode(batch)
        queueCustodyWork("recovery-batch-${request.requestId.take(8)}") {
            runCatching {
                client.sendMessage(
                    recipientMainDht = sourceMainDht,
                    payload = bytes,
                    expiresAtSeconds = ((now + RECOVERY_RESPONSE_TTL_MS) / 1000L),
                    preferDirect = true,
                )
                logger("[groups-v2] RECOVERY_BATCH request=${request.requestId.take(8)} group=${short(request.groupId)} events=${batch.events.size} more=${batch.moreAvailable} target=${short(sourceMainDht)} bytes=${bytes.size}")
            }.onFailure {
                logger("[groups-v2] RECOVERY_BATCH_SEND_FAILED request=${request.requestId.take(8)} target=${short(sourceMainDht)} error=${it.message.orEmpty().take(180)}")
            }
        }
    }

    private fun receiveCustodyRecoveryBatchV2(batch: CustodyRecoveryBatchV2, sourceMainDht: String) {
        val custody = custodyStoreV2 ?: return
        if (batch.protocolVersion != 2 || batch.custodianMainDht != sourceMainDht) return
        val pending = outboundRecoveryRequests[batch.requestId] ?: run {
            logger("[groups-v2] RECOVERY_BATCH_REJECT request=${batch.requestId.take(8)} source=${short(sourceMainDht)} reason=unsolicited")
            return
        }
        if (pending.groupId != batch.groupId || pending.custodianMainDht != sourceMainDht) return
        val batchNow = System.currentTimeMillis()
        if (kotlin.math.abs(batchNow - batch.generatedAt) > RECOVERY_BATCH_FRESHNESS_MS) {
            outboundRecoveryRequests.remove(batch.requestId)
            logger("[groups-v2] RECOVERY_BATCH_REJECT request=${batch.requestId.take(8)} source=${short(sourceMainDht)} reason=stale")
            return
        }
        if (trustStoreV2?.protocolEligible(sourceMainDht) == false) {
            outboundRecoveryRequests.remove(batch.requestId)
            logger("[groups-v2] RECOVERY_BATCH_REJECT request=${batch.requestId.take(8)} source=${short(sourceMainDht)} reason=quarantined")
            return
        }
        if (!verifyCustodySignatureV2(batch.signingPublicKeyHex, CustodyRecoveryBatchV2.SIGNING_DOMAIN, batch.unsignedCanonicalBytes(), batch.signatureHex)) {
            trustStoreV2?.recordEvidence(
                peerMainDht = sourceMainDht,
                kind = GroupReputationEvidenceKindV2.InvalidRecoveryBatchSignature,
                evidenceKey = batch.responseId,
                groupId = batch.groupId,
            )
            logger("[groups-v2] RECOVERY_BATCH_REJECT request=${batch.requestId.take(8)} source=${short(sourceMainDht)} reason=signature")
            return
        }
        outboundRecoveryRequests.remove(batch.requestId)
        var newestCreatedAt = 0L
        var newestEventId = ""
        var accepted = 0
        batch.events.forEach { event ->
            if (event.groupId != batch.groupId) return@forEach
            receiveCanonicalEventFromCustodyV2(event, sourceMainDht)
            if (event.createdAt > newestCreatedAt || (event.createdAt == newestCreatedAt && event.eventId > newestEventId)) {
                newestCreatedAt = event.createdAt
                newestEventId = event.eventId
            }
            accepted++
        }
        val totalRecovered = pending.totalEvents + accepted
        logger("[groups-v2] RECOVERY_EVENT request=${batch.requestId.take(8)} group=${short(batch.groupId)} accepted=$accepted total=$totalRecovered page=${pending.pageNumber} source=${short(sourceMainDht)}")
        if (batch.moreAvailable && batch.nextCursor.isNotBlank()) {
            val looped = batch.nextCursor == pending.cursor || batch.nextCursor == pending.previousCursor
            val capped = pending.pageNumber >= MAX_RECOVERY_PAGES || totalRecovered >= MAX_RECOVERY_EVENTS_PER_SESSION
            if (!looped && !capped) {
                sendCustodyRecoveryRequestV2(
                    groupId = pending.groupId,
                    custodianMainDht = pending.custodianMainDht,
                    sinceCreatedAt = pending.sinceCreatedAt,
                    untilCreatedAt = pending.untilCreatedAt,
                    cursor = batch.nextCursor,
                    branchIds = pending.branchIds,
                    pageNumber = pending.pageNumber + 1,
                    totalEvents = totalRecovered,
                    previousCursor = pending.cursor,
                )
                return
            }
            logger("[groups-v2] RECOVERY_PAGINATION_STOP group=${short(batch.groupId)} source=${short(sourceMainDht)} reason=${if (looped) "cursor-loop" else "session-cap"} page=${pending.pageNumber} total=$totalRecovered")
        }
        pending.branchIds.forEach { branchId ->
            custody.markRecoveryCompleted(batch.groupId, branchId, newestCreatedAt, newestEventId)
        }
        logger("[groups-v2] RECOVERY_COMPLETE group=${short(batch.groupId)} source=${short(sourceMainDht)} events=$accepted")
    }

    private fun receiveCanonicalEventFromCustodyV2(event: SignedGroupEventV2, custodianMainDht: String) {
        val ownedBranches = store.branchesFor(event.groupId)
            .filter { it.ownerMainDht == sessionMainDht }
            .map { it.branchId }
        if (ownedBranches.isEmpty()) return
        val stored = eventStoreV2?.ingest(
            event = event,
            transport = GroupEventTransportV2.CustodyRecovery,
            sourcePeer = custodianMainDht,
            branchIdsForLocalState = ownedBranches,
        ) ?: return
        recordAuthenticatedEquivocationEvidenceV2(stored)
        mirrorBranchStatesV2(stored)
        if (stored.validation != GroupEventValidationV2.Valid) {
            trustStoreV2?.recordEvidence(
                peerMainDht = custodianMainDht,
                kind = GroupReputationEvidenceKindV2.InvalidRecoveredEvent,
                evidenceKey = event.eventId,
                groupId = event.groupId,
                eventId = event.eventId,
                detail = stored.validation.name,
            )
            return
        }
        trustStoreV2?.recordEvidence(
            peerMainDht = custodianMainDht,
            kind = GroupReputationEvidenceKindV2.RecoveryCanonicalVerified,
            evidenceKey = event.eventId,
            groupId = event.groupId,
            eventId = event.eventId,
        )
        enqueueContentValidationV2(
            eventId = event.eventId,
            transport = GroupEventTransportV2.CustodyRecovery,
            // Content belongs to the event author; the custodian is only recovery evidence.
            sourceMainDht = event.authorMainDht,
            publicExpiresAt = 0L,
            delayMs = 0L,
            reason = "custody-recovery",
        )
    }

    private fun sendCustodyRecoveryRequestV2(
        groupId: String,
        custodianMainDht: String,
        sinceCreatedAt: Long,
        untilCreatedAt: Long,
        cursor: String,
        branchIds: List<String>,
        pageNumber: Int = 1,
        totalEvents: Int = 0,
        previousCursor: String = "",
    ) {
        val now = System.currentTimeMillis()
        pruneOutboundRecoveryRequestsV2(now)
        if (outboundRecoveryRequests.size >= MAX_OUTBOUND_RECOVERY_REQUESTS) {
            logger("[groups-v2] RECOVERY_REQUEST_SKIP group=${short(groupId)} custodian=${short(custodianMainDht)} reason=outbound-cap")
            return
        }
        if (cursor.encodeToByteArray().size > MAX_RECOVERY_CURSOR_BYTES || pageNumber !in 1..MAX_RECOVERY_PAGES || totalEvents > MAX_RECOVERY_EVENTS_PER_SESSION) return
        val request = signCustodyRecoveryRequestV2(groupId, sinceCreatedAt, untilCreatedAt, cursor)
        outboundRecoveryRequests[request.requestId] = OutboundCustodyRecoveryV2(
            groupId = groupId,
            custodianMainDht = custodianMainDht,
            sinceCreatedAt = sinceCreatedAt,
            untilCreatedAt = untilCreatedAt,
            branchIds = branchIds.distinct().take(64),
            createdAt = now,
            pageNumber = pageNumber,
            totalEvents = totalEvents,
            cursor = cursor,
            previousCursor = previousCursor,
        )
        queueCustodyWork("recovery-request-${request.requestId.take(8)}") {
            runCatching {
                val bytes = GroupCustodyWireCodecV2.encode(request)
                client.sendMessage(
                    recipientMainDht = custodianMainDht,
                    payload = bytes,
                    expiresAtSeconds = ((System.currentTimeMillis() + RECOVERY_RESPONSE_TTL_MS) / 1000L),
                    preferDirect = true,
                )
                logger("[groups-v2] RECOVERY_SOURCE request=${request.requestId.take(8)} group=${short(groupId)} custodian=${short(custodianMainDht)} cursor=${cursor.take(24)}")
            }.onFailure {
                outboundRecoveryRequests.remove(request.requestId)
                logger("[groups-v2] RECOVERY_REQUEST_SEND_FAILED request=${request.requestId.take(8)} group=${short(groupId)} custodian=${short(custodianMainDht)} error=${it.message.orEmpty().take(180)}")
            }
        }
    }

    private fun signCustodyStoreRequestV2(
        event: SignedGroupEventV2,
        requesterMainDht: String,
        requestedRetainUntil: Long,
    ): CustodyStoreRequestV2 {
        val identity = client.appSigningIdentity()
        val unsigned = CustodyStoreRequestV2(
            requestId = UUID.randomUUID().toString(),
            groupId = event.groupId,
            event = event,
            requestedRetainUntil = requestedRetainUntil,
            receiptRequested = true,
            requesterMainDht = requesterMainDht,
            sentAt = System.currentTimeMillis(),
            signingKeyGeneration = identity.keyGeneration,
            signingPublicKeyHex = identity.publicKeyHex.lowercase(),
            signatureHex = "",
        )
        val signature = client.signAppPayload(CustodyStoreRequestV2.SIGNING_DOMAIN, unsigned.unsignedCanonicalBytes())
        return unsigned.copy(signatureHex = signature.signatureHex.lowercase())
    }

    private fun signCustodyReceiptV2(stored: CustodyEventRecordV2): CustodyReceiptV2 {
        val identity = client.appSigningIdentity()
        val now = System.currentTimeMillis()
        val unsigned = CustodyReceiptV2(
            receiptId = UUID.randomUUID().toString(),
            groupId = stored.groupId,
            eventId = stored.eventId,
            canonicalDigestHex = stored.canonicalDigestHex,
            custodianMainDht = sessionMainDht,
            storedAt = stored.firstStoredAt,
            retainUntil = stored.retainUntil,
            retentionClass = stored.retentionClass,
            signingKeyGeneration = identity.keyGeneration,
            signingPublicKeyHex = identity.publicKeyHex.lowercase(),
            signatureHex = "",
        )
        val signature = client.signAppPayload(CustodyReceiptV2.SIGNING_DOMAIN, unsigned.unsignedCanonicalBytes())
        return unsigned.copy(signatureHex = signature.signatureHex.lowercase())
    }

    private fun signCustodyRecoveryRequestV2(
        groupId: String,
        sinceCreatedAt: Long,
        untilCreatedAt: Long,
        cursor: String,
    ): CustodyRecoveryRequestV2 {
        val identity = client.appSigningIdentity()
        val unsigned = CustodyRecoveryRequestV2(
            requestId = UUID.randomUUID().toString(),
            groupId = groupId,
            requesterMainDht = sessionMainDht,
            sinceCreatedAt = sinceCreatedAt,
            untilCreatedAt = untilCreatedAt,
            cursor = cursor,
            maxEvents = GroupCustodyWireCodecV2.MAX_RECOVERY_EVENTS,
            sentAt = System.currentTimeMillis(),
            signingKeyGeneration = identity.keyGeneration,
            signingPublicKeyHex = identity.publicKeyHex.lowercase(),
            signatureHex = "",
        )
        val signature = client.signAppPayload(CustodyRecoveryRequestV2.SIGNING_DOMAIN, unsigned.unsignedCanonicalBytes())
        return unsigned.copy(signatureHex = signature.signatureHex.lowercase())
    }

    private fun signSizedRecoveryBatchV2(
        request: CustodyRecoveryRequestV2,
        page: CustodyRecoveryPageV2,
    ): CustodyRecoveryBatchV2 {
        val identity = client.appSigningIdentity()
        var events = page.events.take(GroupCustodyWireCodecV2.MAX_RECOVERY_EVENTS)
        while (true) {
            val cutForSize = events.size < page.events.size
            val last = events.lastOrNull()
            val unsigned = CustodyRecoveryBatchV2(
                requestId = request.requestId,
                responseId = UUID.randomUUID().toString(),
                groupId = request.groupId,
                custodianMainDht = sessionMainDht,
                events = events,
                moreAvailable = page.moreAvailable || cutForSize,
                nextCursor = last?.let { "${it.createdAt}|${it.eventId}" }.orEmpty(),
                generatedAt = System.currentTimeMillis(),
                signingKeyGeneration = identity.keyGeneration,
                signingPublicKeyHex = identity.publicKeyHex.lowercase(),
                signatureHex = "",
            )
            val signature = client.signAppPayload(CustodyRecoveryBatchV2.SIGNING_DOMAIN, unsigned.unsignedCanonicalBytes())
            val signed = unsigned.copy(signatureHex = signature.signatureHex.lowercase())
            val encoded = GroupCustodyWireCodecV2.encodeRecoveryBatch(signed, includeSignature = true)
            if (encoded.size <= GroupCustodyWireCodecV2.MAX_RECOVERY_BATCH_BYTES || events.isEmpty()) return signed
            events = events.dropLast(1)
        }
    }

    private fun verifyCustodySignatureV2(
        publicKeyHex: String,
        domain: String,
        payload: ByteArray,
        signatureHex: String,
    ): Boolean = runCatching {
        signatureHex.length == 128 && publicKeyHex.length == 64 &&
            client.verifyAppSignature(publicKeyHex, domain, payload, signatureHex)
    }.getOrDefault(false)

    private fun mirrorBranchStatesV2(stored: StoredGroupEventV2) {
        val custody = custodyStoreV2 ?: return
        stored.branchStates.forEach { (branchId, state) ->
            custody.recordBranchDecision(
                groupId = stored.event.groupId,
                branchId = branchId,
                eventId = stored.event.eventId,
                state = state,
                decidedByMainDht = if (state in TERMINAL_BRANCH_STATES_V2) sessionMainDht else "",
            )
        }
    }

    private fun markBranchStateV2(eventId: String, branchId: String, state: GroupBranchEventStateV2) {
        val stored = eventStoreV2?.get(eventId)
        eventStoreV2?.markBranchState(eventId, branchId, state)
        stored?.event?.let { event ->
            custodyStoreV2?.recordBranchDecision(
                groupId = event.groupId,
                branchId = branchId,
                eventId = eventId,
                state = state,
                decidedByMainDht = if (state in TERMINAL_BRANCH_STATES_V2) sessionMainDht else "",
            )
        }
    }

    private data class OutboundCustodyRecoveryV2(
        val groupId: String,
        val custodianMainDht: String,
        val sinceCreatedAt: Long,
        val untilCreatedAt: Long,
        val branchIds: List<String>,
        val createdAt: Long,
        val pageNumber: Int,
        val totalEvents: Int,
        val cursor: String,
        val previousCursor: String,
    )

    fun receiveOpenServiceRequest(raw: JSONObject) {
        val bytes = runCatching {
            Base64.decode(raw.getString("payload_base64"), Base64.DEFAULT)
        }.getOrNull() ?: return
        val requester = raw.optString("requester_main_dht")
            .ifBlank { raw.optString("sender_main_dht") }
        if (requester.isBlank() || !abuseGuardV2.allow(GroupAbuseActionV2.OpenServiceRequest, requester)) return

        // Groups-v2 compact public witness. The signed group-scoped event is authoritative
        // evidence; the ServiceRequest requester must still match the claimed author.
        GroupEventWitnessCodecV2.decode(bytes)?.let { event ->
            logger("[groups-v2] WITNESS_DECODE event=${event.eventId.take(8)} payload_bytes=${bytes.size} encrypted=false")
            receiveCanonicalEventV2(
                packet = GroupEventTransportPacketV2(event),
                sourceMainDht = requester,
                transport = GroupEventTransportV2.PublicWitness,
                publicExpiresAt = raw.optLong("expires_at"),
            )
            return
        }

        // Compatibility with any short-lived Phase-2 JSON witness requests already in flight.
        GroupEventTransportPacketV2.fromBytes(bytes)?.let { packet ->
            receiveCanonicalEventV2(
                packet = packet,
                sourceMainDht = requester,
                transport = GroupEventTransportV2.PublicWitness,
                publicExpiresAt = raw.optLong("expires_at"),
            )
            return
        }

        // Member-only Groups-v2 witness copies are compact AES-GCM ciphertext until a locally held
        // intake key works.
        if (GroupEventTransportCryptoV2.looksEncrypted(bytes)) {
            for ((groupId, key) in store.privateIntakeKeys()) {
                val packet = GroupEventTransportCryptoV2.decrypt(bytes, key) ?: continue
                if (packet.event.groupId != groupId) continue
                logger("[groups-v2] WITNESS_DECODE event=${packet.event.eventId.take(8)} payload_bytes=${bytes.size} encrypted=true")
                receiveCanonicalEventV2(
                    packet = packet,
                    sourceMainDht = requester,
                    transport = GroupEventTransportV2.PublicWitness,
                    publicExpiresAt = raw.optLong("expires_at"),
                )
                return
            }
            return
        }

        // Public/unlisted legacy intake is readable as a normal GroupWireEnvelope.
        GroupWireEnvelope.fromBytes(bytes)?.let { envelope ->
            logger("groups: spectator intake kind=${envelope.event.kind.name} group=${envelope.event.groupId} requester=${requester.ifBlank { "unknown" }} target=${envelope.targetBranchOwner.ifBlank { "*" }} expires=${raw.optLong("expires_at")}")
            if (requester.isNotBlank() && envelope.event.actorMainDht != requester) return
            if (!store.markEventSeen(envelope.event.eventId)) return

            branches.receiveOpenServiceRequest(raw)
            val mine = store.branchesFor(envelope.event.groupId)
                .filter { it.ownerMainDht == ownMainDht() }
            // Spectator-readable intake is a shared temporary catch-up path. A claimer that comes
            // online after publication may review the same still-live submission even when the
            // request was initially addressed to the Original owner.
            mine.forEach { maybeAutoAccept(it, envelope) }

            if (envelope.targetBranchOwner == ownMainDht()) {
                val others = authorities(envelope.event.groupId).filter { it.ownerMainDht != ownMainDht() }
                if (others.isNotEmpty()) branches.sendPrivateAuthorityEvent(envelope, others)
            }
            return
        }

        // Member-only intake uses the same spectator-readable ServiceRequest primitive, but its
        // payload is opaque AES-GCM ciphertext. Try only locally held private-group keys.
        if (!GroupIntakeCrypto.looksEncrypted(bytes)) return
        for ((groupId, key) in store.privateIntakeKeys()) {
            val envelope = GroupIntakeCrypto.decrypt(bytes, key) ?: continue
            if (envelope.event.groupId != groupId) continue
            if (requester.isNotBlank() && envelope.event.actorMainDht != requester) return
            if (!store.markEventSeen(envelope.event.eventId)) return
            store.rememberEncryptedSpectatorPending(
                envelope.event,
                envelope.contentRef,
                raw.optLong("expires_at"),
            )
            if (envelope.targetBranchOwner == ownMainDht() &&
                envelope.event.kind == GroupEventKind.PostSubmitted) {
                store.addModerationTask(
                    GroupModerationTask(
                        taskId = envelope.event.eventId,
                        groupId = groupId,
                        kind = GroupModerationKind.QuarantinedPost,
                        objectRef = envelope.contentRef,
                        reporterId = envelope.event.actorMainDht,
                        reason = "Encrypted private-group intake (unreviewed)",
                        createdAt = envelope.event.createdAt,
                    )
                )
            }
            return
        }
    }

    fun subscribeOpenIntake(onClosed: (String) -> Unit = {}): Long =
        client.subscribeServiceRequests(
            serviceIdsHex = listOf(
                GroupBranchNetwork.OPEN_INTAKE_SERVICE_ID,
                GroupBranchNetwork.PRIVATE_INTAKE_SERVICE_ID,
                GroupBranchNetwork.V2_PUBLIC_WITNESS_SERVICE_ID,
                GroupBranchNetwork.V2_PRIVATE_WITNESS_SERVICE_ID,
            ),
            onRequest = ::receiveOpenServiceRequest,
            onClosed = onClosed,
        )

    /** Hook for the private-group invitation/join flow once it delivers a group intake key. */
    fun installPrivateGroupIntakeKey(groupId: String, key: ByteArray) {
        store.installPrivateIntakeKey(groupId, key)
    }

    fun publishMyDirectory(): Int {
        val storeId = profileStoreId() ?: return 0
        val me = ownMainDht()
        if (me.isBlank()) return 0
        val entries = store.directoryEntriesForSelf(me)
        directory.publish(storeId, me, entries)
        return entries.size
    }

    private fun deliverSubmission(group: GroupRecord, envelope: GroupWireEnvelope) {
        val targets = authorities(group.groupId)
        require(targets.isNotEmpty()) { "No creator/claimer authority is known for this group" }
        logger("groups: submit ${envelope.event.kind.name} group=${group.name} event=${envelope.event.eventId} authorities=${targets.size}")
        if (group.policy.visibility == GroupVisibility.MembersOnly) {
            // Private groups cannot have claimers. Their temporary offline intake is still a
            // spectator-readable ServiceRequest, but only members holding the 256-bit group
            // intake key can decrypt it. Until an invite has installed that key, fall back to
            // ordinary authenticated/private mailbox delivery rather than leaking plaintext.
            val creator = targets.firstOrNull { it.kind == GroupBranchKind.Original } ?: targets.first()
            val key = store.privateIntakeKey(group.groupId)
            if (key != null) {
                branches.publishEncryptedPrivateIntake(envelope, creator, key)
            } else {
                branches.sendPrivateAuthorityEvent(envelope, listOf(creator))
            }
        } else {
            branches.publishOpenIntake(envelope, targets)
        }
    }

    private fun authorities(groupId: String): List<GroupBranchPointer> {
        val known = store.branchesFor(groupId)
        if (known.isNotEmpty()) return known
        val group = store.byId(groupId) ?: return emptyList()
        // Transport only needs the creator's main DHT. A just-created public group may not have
        // finished publishing its branch root yet, so do not drop the authority during that race.
        if (group.ownerId.isBlank()) return emptyList()
        return listOf(
            GroupBranchPointer(
                groupId = groupId,
                branchId = GroupBranchNetwork.originalBranchId(groupId),
                branchRoot = group.rootRecordKey,
                ownerMainDht = group.ownerId,
                kind = GroupBranchKind.Original,
                creatorRoot = group.rootRecordKey,
                updatedAt = group.updatedAt,
            )
        )
    }

    private fun isKnownAuthoritySender(groupId: String, sourceMainDht: String): Boolean =
        store.branchesFor(groupId).any { it.ownerMainDht == sourceMainDht }

    private fun reconcileFrom(remoteHeader: GroupBranchHeader) {
        if (remoteHeader.ownerMainDht == ownMainDht()) return
        val remoteEvents = branches.readEvents(remoteHeader.branchRoot, 1024)
        if (remoteEvents.isEmpty()) return
        store.branchesFor(remoteHeader.group.groupId)
            .filter { it.ownerMainDht == ownMainDht() && it.branchId != remoteHeader.branchId }
            .forEach { localPointer ->
                val localHeader = branches.readHeader(localPointer.branchRoot) ?: return@forEach
                branches.reconcileObservedEvents(localHeader, remoteEvents)
            }
    }

    private fun addVisiblePostToPulse(
        header: GroupBranchHeader,
        event: GroupEvent,
        ref: WeaveObjectRef,
    ) {
        val message = content.fetch(ref) ?: return
        // This is an owner-side read immediately before rewriting the Pulse; the local value is
        // sufficient and avoids a forced network lookup for every accepted post/comment.
        val current = branches.readPulse(header.branchRoot, force = false) ?: GroupPulse(header.group.groupId)
        val conversationId = event.conversationId
        if (!conversationId.startsWith("post:")) return
        val existing = current.conversations.firstOrNull { it.conversation.objectId == conversationId }
        val preview = MessagePreview(
            messageId = message.messageId,
            authorId = message.authorId,
            authorName = message.authorName,
            bodyPreview = message.body.take(320),
            title = message.title,
            thumbnailBase64 = message.thumbnailBase64,
            fullMessage = ref,
            createdAt = message.createdAt,
            pinned = message.messageId in header.pinnedPostIds,
            hasAudio = message.fullMedia.any { it.type == WeaveObjectType.Audio },
            curatedBy = message.curatedBy,
        )
        val isRoot = message.messageId == postIdFromConversation(conversationId) && message.root == null
        val oldComments = existing?.recentMessages.orEmpty()
        val updated = if (isRoot) {
            (existing ?: GroupConversationPreview(
                conversation = WeaveObjectRef(conversationId, type = WeaveObjectType.Conversation),
                title = message.title ?: "Post",
                lastActivity = message.createdAt,
            )).copy(
                title = message.title ?: existing?.title ?: "Post",
                rootMessage = preview,
                lastActivity = maxOf(existing?.lastActivity ?: 0L, message.createdAt),
                recentUniqueAuthors = (oldComments.map { it.authorId } + message.authorId).toSet().size,
                approximateParticipants = (oldComments.map { it.authorId } + message.authorId).toSet().size,
                activityScore = (existing?.activityScore ?: 0.0) + 1.0,
            )
        } else {
            val root = existing?.rootMessage ?: return
            val comments = (oldComments.filterNot { it.messageId == message.messageId } + preview)
                .sortedWith(compareByDescending<MessagePreview> { it.pinned }.thenByDescending { it.createdAt })
                .take(12)
            existing.copy(
                lastActivity = maxOf(existing.lastActivity, message.createdAt),
                recentMessages = comments,
                replyCount = if (oldComments.any { it.messageId == message.messageId }) {
                    existing.replyCount
                } else {
                    existing.replyCount + 1
                },
                recentUniqueAuthors = (listOf(root.authorId) + comments.map { it.authorId }).toSet().size,
                approximateParticipants = (listOf(root.authorId) + comments.map { it.authorId }).toSet().size,
                activityScore = existing.activityScore + 1.0,
            )
        }
        val next = current.copy(
            generation = current.generation + 1,
            updatedAt = System.currentTimeMillis(),
            conversations = (current.conversations.filterNot { it.conversation.objectId == conversationId } + updated)
                .filter { it.rootMessage != null }
                .sortedByDescending { it.lastActivity }
                .take(GroupPulse.MAX_PULSE_CONVERSATIONS),
            removedPosts = if (isRoot) current.removedPosts.filterNot { it.postId == message.messageId }
            else current.removedPosts,
        )
        branches.publishPulse(header, next)
    }

    private fun removePostFromPulse(
        header: GroupBranchHeader,
        conversationId: String,
        postId: String,
        reason: String = "",
        removedBy: String = "",
    ) {
        // Owner-side mutation: use the locally current Pulse instead of forcing a remote refresh.
        val current = branches.readPulse(header.branchRoot, force = false) ?: return
        val isRoot = conversationId.startsWith("post:") && postIdFromConversation(conversationId) == postId
        val removedConversation = current.conversations.firstOrNull { it.conversation.objectId == conversationId }
        val conversations = if (isRoot) {
            current.conversations.filterNot { it.conversation.objectId == conversationId }
        } else {
            current.conversations.map { conversation ->
                if (conversation.conversation.objectId != conversationId) conversation
                else conversation.copy(
                    recentMessages = conversation.recentMessages.filterNot { it.messageId == postId },
                    replyCount = (conversation.replyCount - 1).coerceAtLeast(0),
                )
            }
        }
        val next = current.copy(
            generation = current.generation + 1,
            updatedAt = System.currentTimeMillis(),
            conversations = conversations,
            removedPosts = if (isRoot) {
                val notice = GroupRemovedPostNotice(
                    postId = postId,
                    conversationId = conversationId,
                    title = removedConversation?.title ?: "Removed post",
                    authorName = removedConversation?.rootMessage?.authorName.orEmpty(),
                    reason = reason.trim().take(600),
                    removedBy = removedBy,
                    removedAt = System.currentTimeMillis(),
                )
                (current.removedPosts.filterNot { it.postId == postId } + notice)
                    .sortedByDescending { it.removedAt }
                    .take(GroupPulse.MAX_REMOVED_POST_NOTICES)
            } else current.removedPosts,
        )
        branches.publishPulse(header, next)
    }

    private fun queueCustodyWork(label: String, block: () -> Unit) {
        if (closed.get()) return
        try {
            custodyPool.execute(block)
        } catch (_: RejectedExecutionException) {
            logger("[groups-v2] CUSTODY_WORK_REJECT label=${label.take(48)} queue=${custodyPool.queue.size} reputation_penalty=false")
        }
    }

    private fun pruneOutboundRecoveryRequestsV2(now: Long = System.currentTimeMillis()) {
        outboundRecoveryRequests.entries.removeIf { now - it.value.createdAt > RECOVERY_RESPONSE_TTL_MS }
    }

    fun abuseDiagnosticLinesV2(): List<String> = abuseGuardV2.diagnosticLines()

    companion object {
        private const val CONTENT_FETCH_TIMEOUT_MS = 25_000L
        private const val CUSTODY_WORKERS = 3
        private const val CUSTODY_WORK_QUEUE = 96
        private const val CUSTODY_TARGETS_PER_EVENT = 6
        private const val RECOVERY_CUSTODIANS_PER_GROUP = 4
        private const val MAX_RECOVERY_GROUPS_PER_SWEEP = 6
        private const val MAX_RECOVERY_REQUESTS_PER_SWEEP = 24
        private const val MAX_OUTBOUND_RECOVERY_REQUESTS = 64
        private const val MAX_RECOVERY_PAGES = 8
        private const val MAX_RECOVERY_EVENTS_PER_SESSION = 96
        private const val MAX_RECOVERY_CURSOR_BYTES = 160
        private const val CUSTODY_REQUEST_FRESHNESS_MS = 15L * 60L * 1000L
        private const val CUSTODY_CLOCK_SKEW_MS = 10L * 60L * 1000L
        private const val RECOVERY_REQUEST_FRESHNESS_MS = 15L * 60L * 1000L
        private const val RECOVERY_BATCH_FRESHNESS_MS = 30L * 60L * 1000L
        private const val RECOVERY_WINDOW_GRACE_MS = 10L * 60L * 1000L
        private const val RECOVERY_RESPONSE_TTL_MS = 30L * 60L * 1000L
        private const val RECOVERY_REFRESH_INTERVAL_MS = 10L * 60L * 1000L
        private val TERMINAL_BRANCH_STATES_V2 = setOf(
            GroupBranchEventStateV2.Accepted,
            GroupBranchEventStateV2.Rejected,
            GroupBranchEventStateV2.Ignored,
        )
        private const val CONTENT_FETCH_WORKERS = 3
        private const val CONTENT_RECOVERY_WORKERS = 2
        fun postConversationId(postId: String): String = "post:$postId"
        fun postIdFromConversation(conversationId: String): String = conversationId.removePrefix("post:")
    }

    private fun maybeAutoAccept(
        pointer: GroupBranchPointer,
        envelope: GroupWireEnvelope,
        canonicalEventIdV2: String? = null,
    ) {
        if (pointer.ownerMainDht != ownMainDht()) return
        val event = envelope.event
        if (event.kind !in setOf(GroupEventKind.PostSubmitted, GroupEventKind.ConversationCreated)) return
        val decisionEventId = canonicalEventIdV2 ?: event.metadata["groups_v2_event_id"]
        val decisionKey = "${decisionEventId ?: event.eventId}|${pointer.branchId}"
        if (!branchDecisionInFlight.add(decisionKey)) {
            logger("[groups-v2] BRANCH_DECISION_IN_FLIGHT event=${(decisionEventId ?: event.eventId).take(8)} branch=${short(pointer.branchId)}")
            return
        }
        try {
            if (decisionEventId != null) {
                val knownState = eventStoreV2?.get(decisionEventId)?.branchStates?.get(pointer.branchId)
                if (knownState == GroupBranchEventStateV2.Accepted || knownState == GroupBranchEventStateV2.Rejected) {
                    logger("[groups-v2] BRANCH_DECISION_DUPLICATE event=${decisionEventId.take(8)} branch=${short(pointer.branchId)} state=${knownState.name}")
                    store.clearPending(event.eventId)
                    return
                }
            }

            val header = branches.readHeader(pointer.branchRoot) ?: return
            branches.recordObservedEvent(header, event)

            if (event.actorMainDht in header.bannedMainDhts) {
                logger("groups: rejecting post ${event.objectId} from banned author=${short(event.actorMainDht)} branch=${short(pointer.branchId)}")
                branches.applyDecision(
                    header = header,
                    postId = event.objectId,
                    conversationId = event.conversationId,
                    postHash = event.objectHash,
                    state = GroupPostState.Removed,
                    messageRef = null,
                    actorMainDht = header.ownerMainDht,
                    reason = "Author is banned on this moderation branch",
                )
                decisionEventId?.let { markBranchStateV2(it, pointer.branchId, GroupBranchEventStateV2.Rejected) }
                store.clearPending(event.eventId)
                return
            }

            if (event.kind == GroupEventKind.PostSubmitted) {
                val ref = envelope.contentRef ?: return
                val auto = when (header.group.policy.posting) {
                    GroupPostingPolicy.Everyone -> true
                    GroupPostingPolicy.Established -> false
                    GroupPostingPolicy.Approval -> false
                }
                if (auto) {
                    // DHT-level idempotency protects old/legacy duplicate deliveries as well as v2.
                    val existing = branches.readPostDecision(header.branchRoot, event.objectId)
                    if (existing?.state == GroupPostState.Visible && existing.postHash == event.objectHash) {
                        logger("groups: duplicate accepted post ${event.objectId} ignored on branch=${pointer.branchId}")
                        decisionEventId?.let { markBranchStateV2(it, pointer.branchId, GroupBranchEventStateV2.Accepted) }
                        store.clearPending(event.eventId)
                        return
                    }
                    logger("groups: auto-accepting post ${event.objectId} on branch=${pointer.branchId} policy=${header.group.policy.posting.name}")
                    branches.applyDecision(
                        header = header,
                        postId = event.objectId,
                        conversationId = event.conversationId,
                        postHash = event.objectHash,
                        state = GroupPostState.Visible,
                        messageRef = ref,
                        actorMainDht = header.ownerMainDht,
                        reason = "",
                    )
                    addVisiblePostToPulse(header, event, ref)
                    decisionEventId?.let { markBranchStateV2(it, pointer.branchId, GroupBranchEventStateV2.Accepted) }
                    store.clearPending(event.eventId)
                } else {
                    logger("groups: queued post ${event.objectId} for review on branch=${pointer.branchId} policy=${header.group.policy.posting.name}")
                    decisionEventId?.let { markBranchStateV2(it, pointer.branchId, GroupBranchEventStateV2.Pending) }
                    store.addModerationTask(
                        GroupModerationTask(
                            taskId = decisionEventId ?: event.eventId,
                            groupId = event.groupId,
                            kind = GroupModerationKind.QuarantinedPost,
                            objectRef = ref,
                            reporterId = event.actorMainDht,
                            reason = "Awaiting branch approval",
                            createdAt = event.createdAt,
                        )
                    )
                }
            }
        } finally {
            branchDecisionInFlight.remove(decisionKey)
        }
    }

}
