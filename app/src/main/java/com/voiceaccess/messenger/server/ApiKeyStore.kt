package com.voiceaccess.messenger.server

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Owns the shared secret [server.LocalApiServer] checks on every request
 * (header `X-Api-Key`). This gates access to the user's actual message
 * content over the network, so it's stored in an Android Keystore-backed
 * encrypted prefs file rather than plain SharedPreferences.
 *
 * A key is generated the first time it's needed and reused after that;
 * [regenerateKey] lets the user invalidate it (e.g. after copying it
 * somewhere they no longer trust) from the Settings screen.
 */
class ApiKeyStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun getOrCreateKey(): String =
        prefs.getString(KEY_PREF, null) ?: regenerateKey()

    fun regenerateKey(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val key = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
        prefs.edit().putString(KEY_PREF, key).apply()
        return key
    }

    private companion object {
        const val PREFS_NAME = "voice_access_messenger_api_key"
        const val KEY_PREF = "api_key"
    }
}
