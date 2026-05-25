package com.calliran.gateway.notification

import android.util.Base64
import com.calliran.gateway.util.BridgeLog
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object JobDecryptor {

    private const val TAG = "JobDecryptor"

    fun decrypt(base64Payload: String, hexKey: String): String? {
        return try {
            val combined = Base64.decode(base64Payload, Base64.DEFAULT)
            if (combined.size < 17) {
                BridgeLog.e(TAG, "Payload too short: ${combined.size} bytes")
                return null
            }
            val iv = combined.copyOfRange(0, 16)
            val ciphertext = combined.copyOfRange(16, combined.size)
            val keyBytes = hexKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
            val plaintext = cipher.doFinal(ciphertext)
            String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            BridgeLog.e(TAG, "Decryption failed: ${e.message}")
            null
        }
    }
}
