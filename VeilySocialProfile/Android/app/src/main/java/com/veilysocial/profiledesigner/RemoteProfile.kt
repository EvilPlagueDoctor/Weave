package com.veilysocial.profiledesigner

import androidx.compose.runtime.Immutable

/**
 * A stranger's book, decoded and validated. Deliberately a different type from
 * [ProfileDocument]: nothing that renders one of these can accidentally publish it,
 * and several can be resident at once so swiping has neighbours ready.
 */
@Immutable
class RemoteProfile(
    val mainDht: String,
    val displayName: String,
    val document: ProfileDocument,
    val fetchedAt: Long = System.currentTimeMillis(),
) {
    val pageCount: Int get() = document.pages.size

    fun page(index: Int): Page? = document.pages.getOrNull(index)

    companion object {
        /** Throws if the payload is malformed or fails VSPF validation. */
        fun decode(mainDht: String, profileText: String, fallbackName: String): RemoteProfile {
            val decoded = ProfileCodec.decodeText(profileText)
            val validation = ProfileCodec.validate(decoded)
            require(validation.ok) { validation.message }
            val name = decoded.profileName.ifBlank { fallbackName }.ifBlank { "Untitled" }
            return RemoteProfile(mainDht, name, decoded)
        }
    }
}

/**
 * Bounded cache of decoded remote profiles. Small on purpose — these hold full documents,
 * and holding many of them on a phone is how you get an OOM on a mid-range device.
 */
class RemoteProfileCache(private val capacity: Int = 8) {
    private val entries = LinkedHashMap<String, RemoteProfile>(16, .75f, true)

    @Synchronized
    fun get(mainDht: String): RemoteProfile? = entries[mainDht]

    @Synchronized
    fun put(profile: RemoteProfile) {
        entries[profile.mainDht] = profile
        while (entries.size > capacity) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    @Synchronized
    fun clear() = entries.clear()
}
