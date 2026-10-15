package app.fluide.player.data.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts stream and playlist URLs at rest (they usually contain the subscriber's credentials).
 *
 * Envelope encryption: a random AES-256 data key encrypts the URLs in software (microseconds per URL,
 * so importing 100 000 channels stays fast), and only that data key is wrapped by a non-exportable
 * Android Keystore key (one slow Keystore operation per app start instead of one per URL).
 *
 * Some cheap boxes ship a broken Keystore: the app then keeps working with an unwrapped key
 * ([isHardwareBacked] = false) rather than refusing to play.
 */
class UrlCipher(context: Context) {

    private val prefs = context.getSharedPreferences("crypto", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    /** Cipher.getInstance costs a provider lookup: one instance per thread (an import encrypts 100 000 URLs). */
    private val ciphers = object : ThreadLocal<Cipher>() {
        override fun initialValue(): Cipher = Cipher.getInstance(TRANSFORMATION)
    }

    @Volatile
    private var dataKey: SecretKey? = null

    var isHardwareBacked = false
        private set

    fun warmUp() {
        key()
    }

    fun encrypt(plain: String): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = ciphers.get()!!
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return iv + sealed
    }

    fun decrypt(data: ByteArray): String {
        require(data.size > IV_BYTES) { "ciphertext too short" }
        val cipher = ciphers.get()!!
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
        return String(cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES), Charsets.UTF_8)
    }

    fun decryptOrNull(data: ByteArray?): String? =
        if (data == null) null else runCatching { decrypt(data) }.getOrNull()

    private fun key(): SecretKey {
        dataKey?.let { return it }
        synchronized(this) {
            dataKey?.let { return it }
            val loaded = loadOrCreateDataKey()
            dataKey = loaded
            return loaded
        }
    }

    private fun loadOrCreateDataKey(): SecretKey {
        val wrapped = prefs.getString(PREF_WRAPPED, null)
        val wrapIv = prefs.getString(PREF_WRAP_IV, null)
        val plain = prefs.getString(PREF_PLAIN, null)
        if (wrapped != null && wrapIv != null) {
            runCatching {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, keystoreKey(create = false), GCMParameterSpec(TAG_BITS, decode(wrapIv)))
                isHardwareBacked = true
                return SecretKeySpec(cipher.doFinal(decode(wrapped)), "AES")
            }.onFailure { Log.w(TAG, "Wrapped data key unreadable, URLs must be imported again", it) }
        } else if (plain != null) {
            return SecretKeySpec(decode(plain), "AES")
        }

        val raw = ByteArray(32).also(random::nextBytes)
        val editor = prefs.edit().clear()
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey(create = true))
            editor.putString(PREF_WRAPPED, encode(cipher.doFinal(raw)))
                .putString(PREF_WRAP_IV, encode(cipher.iv))
            isHardwareBacked = true
        } catch (e: Exception) {
            Log.w(TAG, "Android Keystore unavailable, data key stored unwrapped", e)
            editor.putString(PREF_PLAIN, encode(raw))
            isHardwareBacked = false
        }
        editor.commit()
        return SecretKeySpec(raw, "AES")
    }

    private fun keystoreKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Keystore key missing" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val TAG = "UrlCipher"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "fluide.url.kek"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val PREF_WRAPPED = "dek_wrapped"
        const val PREF_WRAP_IV = "dek_iv"
        const val PREF_PLAIN = "dek_plain"
    }
}
