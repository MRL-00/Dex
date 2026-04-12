package com.remodex.android.core.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.remodex.android.core.model.PhoneIdentityState
import com.remodex.android.core.model.SavedRelaySession
import com.remodex.android.core.model.TrustedMacRegistry
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

class AndroidSecureStorage(context: Context, private val json: Json) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("remodex.secure", Context.MODE_PRIVATE)
    private val alias = "remodex_secure_store"

    fun getOrCreatePhoneIdentity(): PhoneIdentityState {
        return read(KEY_PHONE_IDENTITY, PhoneIdentityState.serializer())
            ?: SecureTransportCrypto.generatePhoneIdentity().also {
                write(KEY_PHONE_IDENTITY, PhoneIdentityState.serializer(), it)
            }
    }

    fun readRelaySession(): SavedRelaySession? = read(KEY_RELAY_SESSION, SavedRelaySession.serializer())

    fun writeRelaySession(value: SavedRelaySession?) {
        if (value == null) {
            prefs.edit().remove(KEY_RELAY_SESSION).apply()
        } else {
            write(KEY_RELAY_SESSION, SavedRelaySession.serializer(), value)
        }
    }

    fun readTrustedRegistry(): TrustedMacRegistry {
        return read(KEY_TRUSTED_REGISTRY, TrustedMacRegistry.serializer()) ?: TrustedMacRegistry()
    }

    fun writeTrustedRegistry(value: TrustedMacRegistry) {
        write(KEY_TRUSTED_REGISTRY, TrustedMacRegistry.serializer(), value)
    }

    fun readLastTrustedMacDeviceId(): String? = readPlain(KEY_LAST_TRUSTED_MAC_DEVICE_ID)

    fun writeLastTrustedMacDeviceId(value: String?) {
        writePlain(KEY_LAST_TRUSTED_MAC_DEVICE_ID, value)
    }

    fun hasCompletedOnboarding(): Boolean = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)

    fun setOnboardingCompleted() {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, true).apply()
    }

    fun readSidebarProjectOrder(): List<String> {
        return readPlain(KEY_SIDEBAR_PROJECT_ORDER)
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?: emptyList()
    }

    fun writeSidebarProjectOrder(value: List<String>) {
        val normalized = value.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        writePlain(KEY_SIDEBAR_PROJECT_ORDER, normalized.joinToString("\n"))
    }

    fun clearRelayState() {
        prefs.edit()
            .remove(KEY_RELAY_SESSION)
            .remove(KEY_LAST_TRUSTED_MAC_DEVICE_ID)
            .apply()
    }

    private fun <T> read(key: String, serializer: KSerializer<T>): T? {
        val encrypted = prefs.getString(key, null) ?: return null
        val jsonValue = decrypt(encrypted) ?: return null
        return runCatching { json.decodeFromString(serializer, jsonValue) }.getOrNull()
    }

    private fun <T> write(key: String, serializer: KSerializer<T>, value: T) {
        val plaintext = json.encodeToString(serializer, value)
        prefs.edit().putString(key, encrypt(plaintext)).apply()
    }

    private fun readPlain(key: String): String? = prefs.getString(key, null)?.let(::decrypt)

    private fun writePlain(key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, encrypt(value)).apply()
        }
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val output = iv + encrypted
        return Base64.getEncoder().encodeToString(output)
    }

    private fun decrypt(ciphertext: String): String? {
        return runCatching {
            val raw = Base64.getDecoder().decode(ciphertext)
            val iv = raw.copyOfRange(0, 12)
            val body = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) {
            return existing
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val KEY_PHONE_IDENTITY = "phone_identity"
        const val KEY_RELAY_SESSION = "relay_session"
        const val KEY_TRUSTED_REGISTRY = "trusted_registry"
        const val KEY_LAST_TRUSTED_MAC_DEVICE_ID = "last_trusted_mac_device_id"
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        const val KEY_SIDEBAR_PROJECT_ORDER = "sidebar_project_order"
    }
}
