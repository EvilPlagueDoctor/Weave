package app.weave

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The daemon credential is a bootstrap secret: Weave needs it before it can authenticate, so it
 * cannot live behind the daemon's authenticated private-storage API. Keep it encrypted with an
 * Android Keystore key instead. SharedPreferences contains only opaque ciphertext slots.
 */
class SecureCredentialStore(context: Context) {
    data class Credential(val secretHex: String, val generation: Long)

    private val prefs = context.applicationContext
        .getSharedPreferences("weave_daemon", Context.MODE_PRIVATE)

    private fun profileSuffix(profileId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(profileId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        .take(32)

    private fun slot(profileId: String): String = "credential_${profileSuffix(profileId)}"
    private fun legacyPrefix(profileId: String): String = "profile_${profileSuffix(profileId)}"
    private fun keyAlias(profileId: String): String = "$KEY_ALIAS_PREFIX.${profileSuffix(profileId)}"

    private fun key(profileId: String): SecretKey {
        val alias = keyAlias(profileId)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun read(profileId: String): Credential? {
        val current = prefs.getString(slot(profileId), null)
        if (current != null) {
            val credential = runCatching { decrypt(profileId, current) }.getOrNull() ?: return null
            removeLegacyPlaintext(profileId)
            return credential
        }

        // One-way migration from the older plaintext bootstrap fields. Delete them only after
        // the Keystore-protected copy has been written successfully.
        val prefix = legacyPrefix(profileId)
        val oldSecretKey = "${prefix}_secret_hex"
        val oldGenerationKey = "${prefix}_credential_generation"
        val oldSecret = prefs.getString(oldSecretKey, null) ?: return null
        if (!prefs.contains(oldGenerationKey)) return null
        val migrated = Credential(oldSecret, prefs.getLong(oldGenerationKey, -1L))
        write(profileId, migrated)
        removeLegacyPlaintext(profileId)
        return migrated
    }

    private fun removeLegacyPlaintext(profileId: String) {
        val prefix = legacyPrefix(profileId)
        val oldSecretKey = "${prefix}_secret_hex"
        val oldGenerationKey = "${prefix}_credential_generation"
        if (!prefs.contains(oldSecretKey) && !prefs.contains(oldGenerationKey)) return
        check(prefs.edit().remove(oldSecretKey).remove(oldGenerationKey).commit()) {
            "encrypted daemon credential exists, but legacy plaintext could not be removed"
        }
    }

    fun write(profileId: String, credential: Credential) {
        val plaintext = JSONObject()
            .put("version", 1)
            .put("secret_hex", credential.secretHex)
            .put("generation", credential.generation)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(profileId))
        cipher.updateAAD(slot(profileId).toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext)
        val packed = ByteArray(1 + cipher.iv.size + ciphertext.size)
        packed[0] = cipher.iv.size.toByte()
        cipher.iv.copyInto(packed, 1)
        ciphertext.copyInto(packed, 1 + cipher.iv.size)
        check(prefs.edit().putString(slot(profileId), Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
            "could not persist encrypted daemon credential"
        }
    }

    fun clear(profileId: String) {
        val prefix = legacyPrefix(profileId)
        check(
            prefs.edit()
                .remove(slot(profileId))
                .remove("${prefix}_secret_hex")
                .remove("${prefix}_credential_generation")
                .commit()
        ) { "could not clear stored daemon credential" }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = keyAlias(profileId)
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private fun decrypt(profileId: String, encoded: String): Credential {
        val packed = Base64.decode(encoded, Base64.DEFAULT)
        require(packed.size > 13) { "encrypted credential is truncated" }
        val ivLength = packed[0].toInt() and 0xff
        require(ivLength in 12..32 && packed.size > 1 + ivLength) { "encrypted credential IV is invalid" }
        val iv = packed.copyOfRange(1, 1 + ivLength)
        val ciphertext = packed.copyOfRange(1 + ivLength, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(profileId), GCMParameterSpec(128, iv))
        cipher.updateAAD(slot(profileId).toByteArray(Charsets.UTF_8))
        val root = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        require(root.optInt("version", 0) == 1)
        return Credential(root.getString("secret_hex"), root.getLong("generation"))
    }

    companion object {
        private const val KEY_ALIAS_PREFIX = "weave.bootstrap-credential.v1"
    }
}
