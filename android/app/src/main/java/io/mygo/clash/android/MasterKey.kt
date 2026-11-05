package io.mygo.clash.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The master key that seals the app's data (the desktop app keeps it in
 * the system keyring). It is stored wrapped by an AES key of the Android
 * Keystore, which never leaves the Keystore.
 */
object MasterKey {
    private const val ALIAS = "mygo-clash-master"
    private const val PREF = "master-key"

    fun get(context: Context, dataDir: File): ByteArray {
        val prefs = context.getSharedPreferences("keystore", Context.MODE_PRIVATE)
        prefs.getString(PREF, null)?.let { return unwrap(it) }
        // The prototype kept the key in a file: take it over.
        val legacy = File(dataDir, "secure/.keyring-master-key")
        val key = if (legacy.exists()) {
            Base64.decode(legacy.readText().trim(), Base64.DEFAULT)
        } else {
            ByteArray(32).also { SecureRandom().nextBytes(it) }
        }
        check(prefs.edit().putString(PREF, wrap(key)).commit()) { "could not store the master key" }
        legacy.delete()
        return key
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** iv (12 bytes) + ciphertext, in Base64. */
    private fun wrap(key: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keystoreKey())
        return Base64.encodeToString(c.iv + c.doFinal(key), Base64.NO_WRAP)
    }

    private fun unwrap(s: String): ByteArray {
        val data = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }
}
