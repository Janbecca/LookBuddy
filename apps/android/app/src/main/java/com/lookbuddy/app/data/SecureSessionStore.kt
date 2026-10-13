package com.lookbuddy.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.encodeToString
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSessionStore(context: Context) : SessionVault {
    private val prefs = context.applicationContext.getSharedPreferences("lookbuddy.secure.v1", Context.MODE_PRIVATE)
    private val alias = "lookbuddy.session.aes.v1"
    private var cached: StoredSession? = load()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun load(): StoredSession? = try {
        val value = prefs.getString("session", null)
        if (value == null) null else {
            val pieces = value.split('.')
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
            ApiJson.decodeFromString<StoredSession>(String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), Charsets.UTF_8))
        }
    } catch (_: Exception) { prefs.edit().remove("session").commit(); null }
    @Synchronized override fun read(): StoredSession? = cached
    @Synchronized override fun save(session: StoredSession?) {
        if (session == null) {
            cached = null
            check(prefs.edit().remove("session").commit()) { "无法清理登录存储" }
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(ApiJson.encodeToString(session).toByteArray(Charsets.UTF_8))
            val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." + Base64.encodeToString(encrypted, Base64.NO_WRAP)
            check(prefs.edit().putString("session", value).commit()) { "无法安全保存登录" }
            cached = session
        }
    }
    @Synchronized override fun replace(expected: StoredSession, updated: StoredSession?): Boolean {
        if (cached != expected) return false
        save(updated); return true
    }
}
