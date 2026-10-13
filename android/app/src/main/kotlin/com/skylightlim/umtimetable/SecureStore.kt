package com.skylightlim.umtimetable

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small encrypted key-value store for secrets (the Spectrum token). Values are
 * AES-GCM encrypted with a key that lives in the Android Keystore and never leaves it.
 * A Keystore decryption takes about 5 ms, so a value is decrypted once per process and
 * kept in memory.
 */
object SecureStore {
    private const val ALIAS = "um-timetable-secrets"
    private const val PREFS = "secure"
    private val plain = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Whether a value is stored, without decrypting it. */
    fun has(context: Context, name: String) = plain.containsKey(name) || context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(name)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    fun put(context: Context, name: String, value: String?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (value == null) {
            plain.remove(name)
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray())
        prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
        plain[name] = value
    }

    fun get(context: Context, name: String): String? {
        plain[name]?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(name, null) ?: return null
        return runCatching {
            val sealed = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
            String(cipher.doFinal(sealed, 12, sealed.size - 12))
        }.onSuccess { plain[name] = it }
            // A value the key can no longer open (e.g. the key was reset) is dropped, so has() agrees.
            .onFailure { prefs.edit().remove(name).apply() }
            .getOrNull()
    }
}
