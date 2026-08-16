package com.veilysocial.profiledesigner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Base64
import com.example.veilknit_deamon.ipc.IVeilKnitApi
import com.example.veilknit_deamon.ipc.IVeilKnitStreamCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DaemonClient(private val context: Context) {
    companion object {
        const val PROTOCOL_VERSION = 3
        const val APP_ID = "veilknit.veilysocial.profile.v1"
        const val APP_NAME = "VeilySocial Profiles"
        private const val DAEMON_PACKAGE = "com.example.veilknit_deamon"
        private const val DAEMON_ACTION = "com.example.veilknit_deamon.BIND_LOCAL_API"
        private const val DAEMON_PERMISSION = "com.example.veilknit_deamon.permission.BIND_VEILKNIT_API"
        private val CAPABILITIES = listOf(
            "SendMessages", "ReceiveMessages", "ManageOwnStorage", "ReadOwnStorage",
            "ReadPublicProfiles", "SubscribeNetworkStatus", "SignAppData"
        )
        private val PROOF_DOMAIN = "veilknit/app-auth/v2".toByteArray(Charsets.UTF_8)
    }

    private var api: IVeilKnitApi? = null
    private var serviceConnection: ServiceConnection? = null
    private val requestId = AtomicLong(1L)
    private var sessionToken: String? = null
    private val prefs = context.getSharedPreferences("veilysocial_profile_daemon", Context.MODE_PRIVATE)

    suspend fun connect(onStatus: (String) -> Unit) {
        api = bindApi()
        ensureCredential(onStatus)
        authenticate()
    }

    fun close() {
        serviceConnection?.let { runCatching { context.unbindService(it) } }
        serviceConnection = null
        api = null
    }

    private suspend fun bindApi(): IVeilKnitApi = suspendCancellableCoroutine { continuation ->
        val intent = Intent(DAEMON_ACTION).setPackage(DAEMON_PACKAGE)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (continuation.isActive) continuation.resume(IVeilKnitApi.Stub.asInterface(service))
            }
            override fun onServiceDisconnected(name: ComponentName?) { api = null }
            override fun onNullBinding(name: ComponentName?) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("VeilKnit API service returned a null binding"))
            }
        }
        serviceConnection = connection

        val bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (security: SecurityException) {
            serviceConnection = null
            continuation.resumeWithException(
                IllegalStateException(
                    "Denied $DAEMON_PERMISSION. The daemon declares this permission at " +
                        "protectionLevel=signature, so both APKs must be signed with the same key. " +
                        "Install the daemon first, then reinstall this app.",
                    security
                )
            )
            return@suspendCancellableCoroutine
        }

        if (!bound) {
            // Android still holds a reference even when bindService() reports failure.
            runCatching { context.unbindService(connection) }
            serviceConnection = null
            continuation.resumeWithException(IllegalStateException(diagnoseBindFailure()))
            return@suspendCancellableCoroutine
        }

        continuation.invokeOnCancellation {
            runCatching { context.unbindService(connection) }
            if (serviceConnection === connection) serviceConnection = null
        }
    }

    /**
     * bindService() returns false without an exception for several unrelated reasons.
     * Work out which one actually applies so the log says something useful.
     */
    private fun diagnoseBindFailure(): String {
        val daemonInstalled = runCatching {
            context.packageManager.getPackageInfo(DAEMON_PACKAGE, 0)
        }.isSuccess
        if (!daemonInstalled) {
            return "VeilKnit daemon package $DAEMON_PACKAGE is not installed, or is not visible to " +
                "this app. Install the daemon, and make sure AndroidManifest.xml declares a " +
                "<queries> entry for it (required on targetSdk 30+)."
        }
        val resolved = context.packageManager.queryIntentServices(
            Intent(DAEMON_ACTION).setPackage(DAEMON_PACKAGE), 0
        )
        if (resolved.isEmpty()) {
            return "The daemon is installed but exposes no service for $DAEMON_ACTION. " +
                "Check that the installed daemon build is recent enough to ship VeilKnitApiService."
        }
        if (context.checkSelfPermission(DAEMON_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            return "$DAEMON_PERMISSION was not granted. Declare it with <uses-permission> in " +
                "AndroidManifest.xml, sign both APKs with the same key, and install the daemon " +
                "before this app."
        }
        return "Could not bind the VeilKnit daemon API for an unknown reason. Is the daemon " +
            "foreground service running?"
    }

    private fun rawRequest(action: String, authenticated: Boolean = true, block: JSONObject.() -> Unit = {}): JSONObject {
        val objectId = requestId.getAndIncrement()
        val request = JSONObject()
            .put("protocol_version", PROTOCOL_VERSION)
            .put("request_id", objectId)
            .put("action", action)
        if (authenticated) request.put("session_token", sessionToken ?: error("Not authenticated"))
        request.block()
        val response = JSONObject(api?.transact(request.toString()) ?: error("Daemon API is not bound"))
        if (!response.optBoolean("ok", false)) {
            val error = response.optJSONObject("error")
            throw IllegalStateException("${error?.optString("code", "daemon_error")}: ${error?.optString("message", "unknown daemon error")}")
        }
        return response.optJSONObject("result") ?: JSONObject()
    }

    private suspend fun ensureCredential(onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (prefs.contains("secret_hex") && prefs.contains("credential_generation")) return@withContext
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.toHex()
        val result = rawRequest("request_app_registration", authenticated = false) {
            put("app_id", APP_ID)
            put("display_name", APP_NAME)
            put("requested_capabilities", JSONArray(CAPABILITIES))
            put("request_token_hex", token)
        }
        val requestId = result.getLong("request_id")
        onStatus("Approve the newest $APP_NAME request in the daemon Applications tab (request #$requestId).")
        while (true) {
            delay(1200)
            val status = rawRequest("get_app_registration_status", authenticated = false) {
                put("registration_request_id", requestId)
                put("request_token_hex", token)
            }
            when (status.optString("type")) {
                "app_registration_still_pending" -> Unit
                "app_registration_approved" -> {
                    prefs.edit()
                        .putString("secret_hex", status.getString("secret_hex"))
                        .putLong("credential_generation", status.getLong("credential_generation"))
                        .apply()
                    return@withContext
                }
                "app_registration_rejected" -> error("Application registration rejected: ${status.optString("reason")}")
                "app_registration_expired" -> error("Application registration request expired")
                else -> error("Unexpected registration state: $status")
            }
        }
    }

    private suspend fun authenticate() = withContext(Dispatchers.IO) {
        val begin = rawRequest("begin_authentication", authenticated = false) {
            put("app_id", APP_ID)
            put("requested_capabilities", JSONArray(CAPABILITIES))
        }
        val challengeId = begin.getLong("challenge_id")
        val nonce = begin.getString("nonce_hex").hexToBytes()
        val issuedAt = begin.getLong("issued_at")
        val expiresAt = begin.getLong("expires_at")
        val generation = begin.getLong("credential_generation")
        val capabilities = begin.getJSONArray("requested_capabilities").let { array ->
            List(array.length()) { array.getString(it) }
        }
        val secret = prefs.getString("secret_hex", null)?.hexToBytes() ?: error("Missing app credential")
        require(generation == prefs.getLong("credential_generation", -1)) { "Credential generation changed; clear app data and reauthorize" }
        val proof = computeProof(secret, APP_ID, challengeId, nonce, issuedAt, expiresAt, generation, capabilities)
        val finish = rawRequest("finish_authentication", authenticated = false) {
            put("app_id", APP_ID)
            put("challenge_id", challengeId)
            put("proof_hex", proof.toHex())
        }
        sessionToken = finish.getString("session_token_hex")
    }

    fun identity(): JSONObject = rawRequest("get_identity")
    fun listAppPeers(): JSONObject = rawRequest("list_app_peers") { put("limit", 1000); put("start_search", true) }
    fun getAppRoot(peerMainDht: String): JSONObject = rawRequest("get_app_root") { put("peer_main_dht", peerMainDht); put("start_lookup", true) }
    fun registerAppRoot(rootDht: String): JSONObject = rawRequest("register_app_root") { put("root_dht", rootDht) }
    fun listStores(): JSONObject = rawRequest("list_app_stores")
    fun createStore(name: String, subkeys: Int): JSONObject = rawRequest("create_app_store") {
        put("name", name); put("subkey_count", subkeys); put("initialize", true)
    }
    fun readStore(storeId: String, locations: List<Int>, force: Boolean = false): JSONObject = rawRequest("read_app_store") {
        put("store_id", storeId); put("locations", JSONArray(locations)); put("force_refresh", force)
    }
    fun writeStore(storeId: String, location: Int, bytes: ByteArray): JSONObject = rawRequest("write_app_store") {
        put("store_id", storeId)
        put("writes", JSONArray().put(JSONObject().put("location", location).put("value_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))))
    }
    fun readPublicStore(recordKey: String, locations: List<Int>, force: Boolean = true): JSONObject = rawRequest("read_public_store") {
        put("record_key", recordKey); put("locations", JSONArray(locations)); put("force_refresh", force)
    }
    /**
     * Sends an application message. The daemon tries a live direct route first and falls back
     * to persistent mailbox delivery, so the caller does not care whether the recipient is
     * online. Unlike [sendGossip] the sender is authenticated end to end.
     *
     * Payloads are capped at 8 KiB by the daemon, so this carries pointers, not content.
     */
    fun sendMessage(
        recipientMainDht: String,
        payload: ByteArray,
        conversationIdHex: String? = null,
        preferDirect: Boolean = true,
    ): JSONObject = rawRequest("send_message") {
        put("recipient_main_dht", recipientMainDht)
        put("payload_base64", Base64.encodeToString(payload, Base64.NO_WRAP))
        conversationIdHex?.let { put("conversation_id_hex", it) }
        put("prefer_direct", preferDirect)
    }

    /** Messages that arrived while this app was not subscribed. Summaries only. */
    fun listInbox(): JSONObject = rawRequest("list_inbox")

    /** Full message including its payload. */
    fun readInbox(messageIdHex: String): JSONObject = rawRequest("read_inbox") {
        put("message_id_hex", messageIdHex)
    }

    fun deleteInbox(messageIdHex: String): JSONObject = rawRequest("delete_inbox") {
        put("message_id_hex", messageIdHex)
    }

    /** Asks the daemon to check the mailbox now rather than on its own schedule. */
    fun triggerMessageRetrieval(): JSONObject = rawRequest("trigger_message_retrieval")

    fun getMailboxStatus(): JSONObject = rawRequest("get_mailbox_status")

    fun sendGossip(peerMainDht: String, payload: ByteArray): JSONObject = rawRequest("send_gossip") {
        put("recipient_main_dht", peerMainDht)
        put("payload_base64", Base64.encodeToString(payload, Base64.NO_WRAP))
    }
    fun recommendNodes(nodes: List<String>) = rawRequest("recommend_nodes") {
        put("nodes", JSONArray(nodes.take(256))); put("context", "veilysocial_gossip"); put("ttl_seconds", 600)
    }
    fun setInteractiveActivity(nodes: List<String>) = rawRequest("set_app_activity") {
        put("level", "interactive"); put("relevant_nodes", JSONArray(nodes.take(256))); put("lease_seconds", 90)
    }


    fun beginBlobUpload(contentType: String): JSONObject = rawRequest("begin_blob_upload") { put("content_type", contentType) }
    fun appendBlobUpload(uploadId: String, data: ByteArray): JSONObject = rawRequest("append_blob_upload") {
        put("upload_id", uploadId); put("data_base64", Base64.encodeToString(data, Base64.NO_WRAP))
    }
    fun finishBlobUpload(uploadId: String, expectedSha256Hex: String? = null): JSONObject = rawRequest("finish_blob_upload") {
        put("upload_id", uploadId); if (expectedSha256Hex != null) put("expected_sha256_hex", expectedSha256Hex)
    }
    fun abortBlobUpload(uploadId: String): JSONObject = rawRequest("abort_blob_upload") { put("upload_id", uploadId) }
    fun deleteBlob(blobId: String): JSONObject = rawRequest("delete_blob") { put("blob_id", blobId) }
    fun readBlobRange(rootRecordKey: String, offset: Long, length: Long, force: Boolean = false): JSONObject = rawRequest("read_blob_range") {
        put("root_record_key", rootRecordKey); put("offset", offset); put("length", length); put("force_refresh", force)
    }

    fun uploadBlob(contentType: String, data: ByteArray): JSONObject {
        val started = beginBlobUpload(contentType).getJSONObject("upload")
        val uploadId = started.getString("upload_id")
        try {
            var offset = 0
            while (offset < data.size) {
                val end = minOf(data.size, offset + 256 * 1024)
                appendBlobUpload(uploadId, data.copyOfRange(offset, end))
                offset = end
            }
            return finishBlobUpload(uploadId).getJSONObject("blob")
        } catch (t: Throwable) {
            runCatching { abortBlobUpload(uploadId) }
            throw t
        }
    }

    fun downloadBlob(rootRecordKey: String, maxBytes: Int = 4 * 1024 * 1024): Pair<JSONObject, ByteArray> {
        val meta = readBlobRange(rootRecordKey, 0, 0, true)
        val blob = meta.getJSONObject("blob")
        val total = blob.getLong("total_bytes")
        require(total in 0..maxBytes.toLong()) { "Profile blob is too large: $total bytes" }
        val full = readBlobRange(rootRecordKey, 0, total, false)
        val data = Base64.decode(full.getString("data_base64"), Base64.DEFAULT)
        return full.getJSONObject("blob") to data
    }

    fun publishServiceRequest(intendedHostMainDht: String, serviceIdHex: String, manifestHashHex: String, instanceIdHex: String, payload: ByteArray, delegationAllowed: Boolean = true, spectatorsAllowed: Boolean = false, ttlSeconds: Long = 900): JSONObject = rawRequest("publish_service_request") {
        put("intended_host_main_dht", intendedHostMainDht); put("service_id_hex", serviceIdHex); put("service_manifest_hash_hex", manifestHashHex); put("instance_id_hex", instanceIdHex)
        put("payload_base64", Base64.encodeToString(payload, Base64.NO_WRAP)); put("delegation_allowed", delegationAllowed); put("spectators_allowed", spectatorsAllowed); put("ttl_seconds", ttlSeconds)
    }
    fun withdrawServiceRequest(requestIdHex: String): JSONObject = rawRequest("withdraw_service_request") { put("request_id_hex", requestIdHex) }
    fun sendServiceReply(requestIdHex: String, replyRouteBlobBase64: String, payload: ByteArray): JSONObject = rawRequest("send_service_reply") {
        put("request_id_hex", requestIdHex); put("reply_route_blob_base64", replyRouteBlobBase64); put("payload_base64", Base64.encodeToString(payload, Base64.NO_WRAP))
    }
    fun subscribeServiceRequests(serviceIdsHex: List<String>, onRequest: (JSONObject) -> Unit, onClosed: (String) -> Unit): Long {
        val request = JSONObject().put("protocol_version", PROTOCOL_VERSION).put("request_id", requestId.getAndIncrement()).put("action", "subscribe_service_requests")
            .put("session_token", sessionToken ?: error("Not authenticated")).put("service_ids_hex", JSONArray(serviceIdsHex.take(64)))
        return api?.subscribe(request.toString(), object : IVeilKnitStreamCallback.Stub() {
            override fun onLine(line: String?) { if (!line.isNullOrBlank()) runCatching { val obj=JSONObject(line); if(obj.optString("stream")=="service_requests") onRequest(obj.getJSONObject("event")) } }
            override fun onClosed(reason: String?) { onClosed(reason ?: "Daemon service-request stream closed") }
        }) ?: error("Daemon API is not bound")
    }

    fun subscribeMessages(onMessage: (JSONObject) -> Unit, onClosed: (String) -> Unit): Long {
        val request = JSONObject()
            .put("protocol_version", PROTOCOL_VERSION)
            .put("request_id", requestId.getAndIncrement())
            .put("action", "subscribe_messages")
            .put("session_token", sessionToken ?: error("Not authenticated"))
        return api?.subscribe(request.toString(), object : IVeilKnitStreamCallback.Stub() {
            override fun onLine(line: String?) {
                if (line.isNullOrBlank()) return
                runCatching {
                    val obj = JSONObject(line)
                    if (obj.optString("stream") == "application_messages") onMessage(obj.getJSONObject("event"))
                }
            }
            override fun onClosed(reason: String?) { onClosed(reason ?: "Daemon message stream closed") }
        }) ?: error("Daemon API is not bound")
    }

    fun unsubscribe(id: Long) { runCatching { api?.unsubscribe(id) } }

    private fun computeProof(
        secret: ByteArray, appId: String, challengeId: Long, nonce: ByteArray,
        issuedAt: Long, expiresAt: Long, generation: Long, capabilities: List<String>
    ): ByteArray {
        val chunks = ArrayList<ByteArray>()
        chunks.add(PROOF_DOMAIN)
        chunks.add(le32(appId.toByteArray(Charsets.UTF_8).size))
        chunks.add(appId.toByteArray(Charsets.UTF_8))
        chunks.add(le64(challengeId))
        chunks.add(nonce)
        chunks.add(le64(issuedAt))
        chunks.add(le64(expiresAt))
        chunks.add(le64(generation))
        chunks.add(le32(capabilities.size))
        capabilities.forEach { capability ->
            chunks.add(capability.toByteArray(Charsets.UTF_8))
            chunks.add(byteArrayOf(0))
        }
        val input = ByteArray(chunks.sumOf { it.size })
        var offset = 0
        chunks.forEach { chunk -> chunk.copyInto(input, offset); offset += chunk.size }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(input)
    }

    private fun le32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun le64(value: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
