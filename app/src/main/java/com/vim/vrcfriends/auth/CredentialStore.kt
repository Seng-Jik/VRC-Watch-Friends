package com.vim.vrcfriends.auth

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
 * 用户名/密码落盘（VRC 会话经常被清，需要自动重登）。
 *
 * 优先用 Android Keystore 的 AES-256-GCM 加密；Keystore 不可用时退化为
 * `p:` 前缀的 Base64（手表上没有更好的隔离手段，属于可接受的降级）。
 */
class CredentialStore(ctx: Context) : CredStore {

    data class Creds(val username: String, val password: String)

    private val prefs = ctx.getSharedPreferences("vrc-credentials", Context.MODE_PRIVATE)

    override fun load(): Creds? {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        return runCatching {
            val raw = if (blob.startsWith("p:")) {
                Base64.decode(blob.substring(2), Base64.NO_WRAP)
            } else {
                decrypt(Base64.decode(blob, Base64.NO_WRAP))
            }
            val text = String(raw, Charsets.UTF_8)
            val sep = text.indexOf('\u0000')
            if (sep <= 0) null else Creds(text.substring(0, sep), text.substring(sep + 1))
        }.getOrNull()
    }

    override fun save(username: String, password: String) {
        val raw = "$username\u0000$password".toByteArray(Charsets.UTF_8)
        val blob = runCatching { Base64.encodeToString(encrypt(raw), Base64.NO_WRAP) }
            .getOrElse { "p:" + Base64.encodeToString(raw, Base64.NO_WRAP) }
        prefs.edit().putString(KEY_BLOB, blob).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        return c.iv + c.doFinal(data)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > 13) { "blob too short" }
        val iv = blob.copyOfRange(0, 12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    private companion object {
        const val ALIAS = "vrc-credentials"
        const val KEY_BLOB = "blob"
    }
}
