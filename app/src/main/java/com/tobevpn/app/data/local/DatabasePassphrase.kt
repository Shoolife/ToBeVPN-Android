package com.tobevpn.app.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Generates and securely stores a random passphrase for SQLCipher
 * using Android Keystore for key wrapping.
 */
object DatabasePassphrase {

    private const val KEYSTORE_ALIAS = "tobevpn_db_key"
    private const val PREFS_NAME = "tobevpn_db_prefs"
    private const val PREF_ENCRYPTED = "encrypted_passphrase"
    private const val PREF_IV = "passphrase_iv"

    fun getPassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encryptedB64 = prefs.getString(PREF_ENCRYPTED, null)
        val ivB64 = prefs.getString(PREF_IV, null)

        if (encryptedB64 != null && ivB64 != null) {
            return decrypt(
                android.util.Base64.decode(encryptedB64, android.util.Base64.NO_WRAP),
                android.util.Base64.decode(ivB64, android.util.Base64.NO_WRAP),
            )
        }

        val passphrase = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val (encrypted, iv) = encrypt(passphrase)

        prefs.edit()
            .putString(PREF_ENCRYPTED, android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP))
            .putString(PREF_IV, android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
            .apply()

        return passphrase
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.getEntry(KEYSTORE_ALIAS, null)?.let {
            return (it as KeyStore.SecretKeyEntry).secretKey
        }

        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(spec)
            generateKey()
        }
    }

    private fun encrypt(data: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return cipher.doFinal(data) to cipher.iv
    }

    private fun decrypt(encrypted: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }
}
