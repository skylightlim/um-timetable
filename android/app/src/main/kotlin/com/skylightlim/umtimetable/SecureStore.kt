package com.skylightlim.umtimetable

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
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

    private class MissingKey : Exception("no key")

    /** The Keystore key; made when [create] and there's none yet (only when storing a value). */
    private fun key(create: Boolean = true): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        if (!create) throw MissingKey()
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

    /** Sign out: every value goes, and the key with them (a new one is made for the next sign-in). */
    fun clear(context: Context) {
        plain.clear()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    /**
     * The value, or null. A value the key can never open (the key is gone, reset or invalidated) is
     * dropped, so has() agrees, and [onDropped] is told. Any other failure (a passing Keystore error)
     * keeps it for the next try: dropping it then signed the user out for nothing.
     */
    fun get(context: Context, name: String, onDropped: () -> Unit = {}): String? {
        plain[name]?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(name, null) ?: return null
        return runCatching {
            val sealed = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, sealed, 0, 12))
            String(cipher.doFinal(sealed, 12, sealed.size - 12))
        }.onSuccess { plain[name] = it }
            .onFailure { e ->
                val never = e is AEADBadTagException || e is MissingKey || e is KeyPermanentlyInvalidatedException || e is UnrecoverableKeyException
                Log.w("UMTimetable", "SecureStore: couldn't open $name (${if (never) "dropped" else "kept for the next try"}): $e")
                if (never) {
                    prefs.edit().remove(name).apply()
                    onDropped()
                }
            }
            .getOrNull()
    }
}
