package app.weave

import java.security.MessageDigest

/** Shared SHA-256 helper used by Groups, profile/blob verification, and widget networking. */
internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
