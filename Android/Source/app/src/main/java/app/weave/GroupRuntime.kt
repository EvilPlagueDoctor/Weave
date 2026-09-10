package app.weave

import android.util.Base64
import org.json.JSONObject
import java.util.UUID

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
) {
    private val branches = GroupBranchNetwork(client, store)
    private val directory = GroupDirectoryNetwork(client)

    fun publishOriginal(group: GroupRecord, pulse: GroupPulse): GroupRecord {
        if (group.policy.visibility == GroupVisibility.MembersOnly) {
            // Never leak an encrypted group's identity into public group/profile DHTs.
            // The private membership/key-distribution transport is intentionally separate.
            val local = group.copy(rootRecordKey = "")
            store.updateGroup(local)
            return local
        }
        val (published, header) = branches.publishOriginal(group, pulse, ownMainDht())
        store.rememberBranch(header.pointer(), selectIfNone = true, ownedByMe = true)
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
        store.rememberBranch(header.pointer(), selectIfNone = header.kind == GroupBranchKind.Original)
        header.knownBranches.forEach { store.rememberBranch(it, selectIfNone = false) }
        reconcileFrom(header)
        val pulse = branches.readPulse(pointer.branchRoot)
        pulse?.let { store.upsertPulse(it, pointer.branchId) }
        store.upsertDiscovered(header.group, pulse, pointer.branchId)
        return header.group to pulse
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
        store.rememberBranch(claim.pointer(), selectIfNone = false, ownedByMe = true)

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

    /** A true fork is intentionally a different group identity. */
    fun fork(groupId: String, newName: String): GroupRecord {
        val original = store.byId(groupId) ?: error("Unknown group")
        return store.createGroup(
            ownerId = ownMainDht(),
            name = newName.ifBlank { "${original.name} fork" },
            description = original.description,
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
            store.clearPendingForObject(postId)
        } else {
            branches.sendPrivateAuthorityEvent(
                GroupWireEnvelope(action, if (state == GroupPostState.Visible) messageRef else null),
                listOf(selected),
            )
        }
    }

    fun grantModerator(groupId: String, moderatorMainDht: String): GroupBranchHeader {
        val selected = store.selectedBranch(groupId) ?: error("No selected branch")
        require(selected.ownerMainDht == ownMainDht()) { "Only a branch owner can assign its moderators" }
        val header = branches.readHeader(selected.branchRoot) ?: error("Branch unavailable")
        return branches.grantModerator(
            header,
            GroupModeratorGrant(
                moderatorMainDht = moderatorMainDht,
                grantedAt = System.currentTimeMillis(),
            )
        )
    }

    /** Resolve one actionable moderation item from Group-mode Me. */
    fun resolveModerationTask(taskId: String, approve: Boolean) {
        val task = store.moderationTask(taskId) ?: return
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
        // Initial submissions are authenticated by the daemon. Creator/claimer peers may relay the
        // same original event without rewriting its actor/eventId; only known authorities get that
        // exception, which preserves cross-path deduplication.
        val actorMatchesSource = event.actorMainDht.isBlank() || event.actorMainDht == sourceMainDht
        if (!actorMatchesSource && !isKnownAuthoritySender(event.groupId, sourceMainDht)) return
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
            store.clearPendingForObject(event.objectId)
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

    fun receiveOpenServiceRequest(raw: JSONObject) {
        val bytes = runCatching {
            Base64.decode(raw.getString("payload_base64"), Base64.DEFAULT)
        }.getOrNull() ?: return

        // Public/unlisted intake is readable as a normal GroupWireEnvelope.
        GroupWireEnvelope.fromBytes(bytes)?.let { envelope ->
            val requester = raw.optString("requester_main_dht")
                .ifBlank { raw.optString("sender_main_dht") }
            if (requester.isNotBlank() && envelope.event.actorMainDht != requester) return
            if (!store.markEventSeen(envelope.event.eventId)) return

            branches.receiveOpenServiceRequest(raw)
            if (envelope.targetBranchOwner == ownMainDht()) {
                store.branchesFor(envelope.event.groupId)
                    .filter { it.ownerMainDht == ownMainDht() }
                    .forEach { maybeAutoAccept(it, envelope) }

                val others = authorities(envelope.event.groupId).filter { it.ownerMainDht != ownMainDht() }
                if (others.isNotEmpty()) branches.sendPrivateAuthorityEvent(envelope, others)
            }
            return
        }

        // Member-only intake uses the same spectator-readable ServiceRequest primitive, but its
        // payload is opaque AES-GCM ciphertext. Try only locally held private-group keys.
        if (!GroupIntakeCrypto.looksEncrypted(bytes)) return
        val requester = raw.optString("requester_main_dht")
            .ifBlank { raw.optString("sender_main_dht") }
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
            ),
            onRequest = ::receiveOpenServiceRequest,
            onClosed = onClosed,
        )

    /** Hook for the private-group invitation/join flow once it delivers a group intake key. */
    fun installPrivateGroupIntakeKey(groupId: String, key: ByteArray) {
        store.installPrivateIntakeKey(groupId, key)
    }

    fun publishMyDirectory() {
        val storeId = profileStoreId() ?: return
        val me = ownMainDht()
        if (me.isBlank()) return
        directory.publish(storeId, me, store.directoryEntriesForSelf(me))
    }

    private fun deliverSubmission(group: GroupRecord, envelope: GroupWireEnvelope) {
        val targets = authorities(group.groupId)
        require(targets.isNotEmpty()) { "No creator/claimer authority is known for this group" }
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
        // Private groups deliberately have no public branch root, but the creator's main DHT is
        // still enough to address their encrypted intake/private authority mailbox.
        if (group.rootRecordKey.isBlank() && group.policy.visibility != GroupVisibility.MembersOnly) return emptyList()
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

    private fun maybeAutoAccept(pointer: GroupBranchPointer, envelope: GroupWireEnvelope) {
        if (pointer.ownerMainDht != ownMainDht()) return
        val event = envelope.event
        if (event.kind !in setOf(GroupEventKind.PostSubmitted, GroupEventKind.ConversationCreated)) return
        val header = branches.readHeader(pointer.branchRoot) ?: return
        branches.recordObservedEvent(header, event)

        if (event.kind == GroupEventKind.PostSubmitted) {
            val ref = envelope.contentRef ?: return
            val auto = when (header.group.policy.posting) {
                GroupPostingPolicy.Everyone -> true
                GroupPostingPolicy.Established -> false // trust/tenure hook intentionally requires membership state
                GroupPostingPolicy.Approval -> false
            }
            if (auto) {
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
                store.clearPending(event.eventId)
            } else {
                store.addModerationTask(
                    GroupModerationTask(
                        taskId = event.eventId,
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
    }
}
