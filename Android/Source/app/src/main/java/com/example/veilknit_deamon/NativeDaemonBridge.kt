package com.example.veilknit_deamon

import org.json.JSONArray
import android.content.Context

object NativeDaemonBridge {
    val isLibraryLoaded: Boolean
    val loadError: String?

    init {
        val result = runCatching { System.loadLibrary("veilknit_daemon") }
        isLibraryLoaded = result.isSuccess
        loadError = result.exceptionOrNull()?.message
    }

    @JvmStatic
    private external fun nativeStart(
        context: Context,
        dataDirectory: String,
        signup: Boolean,
        username: String,
        password: String,
    ): Boolean

    @JvmStatic
    private external fun nativeSendCommand(command: String): Boolean

    @JvmStatic
    private external fun nativeRequestStop(): Boolean

    @JvmStatic
    private external fun nativeIsRunning(): Boolean

    @JvmStatic
    private external fun nativeDrainLogs(): String

    @JvmStatic
    private external fun nativeRestoreBackup(
        dataDirectory: String,
        backupPath: String,
        passphrase: String,
    ): String

    @JvmStatic
    private external fun nativeEmbeddedTransact(requestJson: String): String

    @JvmStatic
    private external fun nativeEmbeddedRecoverAppCredential(appId: String): String

    @JvmStatic
    private external fun nativeEmbeddedSubscribe(requestJson: String): Long

    @JvmStatic
    private external fun nativeEmbeddedDrainSubscription(subscriptionId: Long): String

    @JvmStatic
    private external fun nativeEmbeddedSubscriptionActive(subscriptionId: Long): Boolean

    @JvmStatic
    private external fun nativeEmbeddedProfileId(): String

    @JvmStatic
    private external fun nativeEmbeddedUnsubscribe(subscriptionId: Long): Boolean

    fun start(
        context: Context,
        dataDirectory: String,
        signup: Boolean,
        username: String,
        password: String,
    ): Boolean = isLibraryLoaded && runCatching {
        nativeStart(
            context.applicationContext,
            dataDirectory,
            signup,
            username,
            password,
        )
    }.getOrDefault(false)

    fun sendCommand(command: String): Boolean = isLibraryLoaded && runCatching {
        nativeSendCommand(command)
    }.getOrDefault(false)

    fun requestStop(): Boolean = isLibraryLoaded && runCatching {
        nativeRequestStop()
    }.getOrDefault(false)

    fun transact(requestJson: String): String {
        check(isLibraryLoaded) { loadError ?: "The native Rust library is unavailable." }
        return nativeEmbeddedTransact(requestJson)
    }

    /**
     * Same-process recovery hook for an embedded app whose locally saved credential was lost
     * or no longer matches the daemon account. This is intentionally JNI-only: external
     * socket clients cannot use it to rotate another application's credential.
     */
    fun recoverEmbeddedAppCredential(appId: String): String {
        check(isLibraryLoaded) { loadError ?: "The native Rust library is unavailable." }
        return nativeEmbeddedRecoverAppCredential(appId)
    }

    fun subscribe(requestJson: String): Long = if (!isLibraryLoaded) 0L else runCatching {
        nativeEmbeddedSubscribe(requestJson)
    }.getOrDefault(0L)

    fun drainSubscription(subscriptionId: Long): List<String> {
        if (!isLibraryLoaded || subscriptionId <= 0L) return emptyList()
        val raw = runCatching { nativeEmbeddedDrainSubscription(subscriptionId) }.getOrDefault("[]")
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (index in 0 until array.length()) add(array.optString(index))
            }
        }.getOrDefault(emptyList())
    }

    fun subscriptionActive(subscriptionId: Long): Boolean =
        isLibraryLoaded && subscriptionId > 0L && runCatching {
            nativeEmbeddedSubscriptionActive(subscriptionId)
        }.getOrDefault(false)

    fun embeddedProfileId(): String = if (!isLibraryLoaded) "" else runCatching {
        nativeEmbeddedProfileId().trim()
    }.getOrDefault("")

    fun unsubscribe(subscriptionId: Long): Boolean =
        isLibraryLoaded && subscriptionId > 0L && runCatching {
            nativeEmbeddedUnsubscribe(subscriptionId)
        }.getOrDefault(false)

    fun isRunning(): Boolean = isLibraryLoaded && runCatching {
        nativeIsRunning()
    }.getOrDefault(false)

    fun restoreBackup(
        dataDirectory: String,
        backupPath: String,
        passphrase: String,
    ): String = if (!isLibraryLoaded) {
        loadError ?: "The native Rust library is unavailable."
    } else {
        runCatching { nativeRestoreBackup(dataDirectory, backupPath, passphrase) }
            .getOrElse { "Backup restore failed: ${it.message ?: it::class.java.simpleName}" }
    }

    fun drainLogs(): List<String> {
        if (!isLibraryLoaded) return emptyList()
        val raw = runCatching { nativeDrainLogs() }.getOrDefault("[]")
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    add(array.optString(index))
                }
            }
        }.getOrDefault(emptyList())
    }
}
