package app.weave

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption for member-only group spectator-readable intake.
 *
 * The ServiceRequest itself remains visible to the network, but the event/ref payload is opaque.
 * No group ID is placed in this wrapper; a member tries their locally held group intake keys.
 * Key distribution is intentionally separate from this codec.
 */
object GroupIntakeCrypto {
    private const val TYPE = "weave.group.encrypted-intake.v2"
    private val aad = TYPE.encodeToByteArray()
    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(32).also(random::nextBytes)

    fun encrypt(envelope: GroupWireEnvelope, key: ByteArray): ByteArray {
        require(key.size == 32) { "Private group intake key must be 256 bits" }
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(envelope.toBytes())
        return JSONObject()
            .put("type", TYPE)
            .put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .toString()
            .encodeToByteArray()
    }

    fun decrypt(bytes: ByteArray, key: ByteArray): GroupWireEnvelope? = runCatching {
        if (key.size != 32) return null
        val o = JSONObject(bytes.decodeToString())
        if (o.optString("type") != TYPE) return null
        val nonce = Base64.decode(o.getString("nonce"), Base64.DEFAULT)
        val ciphertext = Base64.decode(o.getString("ciphertext"), Base64.DEFAULT)
        if (nonce.size != 12) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        GroupWireEnvelope.fromBytes(cipher.doFinal(ciphertext))
    }.getOrNull()

    fun looksEncrypted(bytes: ByteArray): Boolean = runCatching {
        JSONObject(bytes.decodeToString()).optString("type") == TYPE
    }.getOrDefault(false)
}
