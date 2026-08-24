package com.voiceaccess.messenger.server

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Owns the shared secret [server.LocalApiServer] checks on every request
 * (header `X-Api-Key`). This gates access to the user's actual message
 * content over the network, so it's stored in an Android Keystore-backed
 * encrypted prefs file rather than plain SharedPreferences — normally.
 *
 * `androidx.security:security-crypto` has been stuck in alpha for years and
 * is known to throw on real devices (Keystore/StrongBox quirks vary a lot
 * by OEM/Android version) — and this class used to let that exception
 * propagate straight out of its constructor. Since [ApiServerService]
 * constructs this while starting the foreground service, an uncaught
 * exception here didn't just fail the server: it crashed the whole app
 * process, which is what "server said running, then a moment later it's
 * just stopped" looked like. Falling back to plain (unencrypted)
 * `SharedPreferences` on failure means the app keeps working — a locally
 * generated API key sitting in this app's private storage is a modest
 * exposure next to the whole app crashing.
 *
 * A key is generated the first time it's needed and reused after that;
 * [regenerateKey] lets the user invalidate it (e.g. after copying it
 * somewhere they no longer trust) from the Settings screen.
 */
class ApiKeyStore(context: Context) {

    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.e(TAG, "EncryptedSharedPreferences.create failed on this device — falling back to plain " +
            "SharedPreferences so the app doesn't crash. The API key is still private to this app, just " +
            "not Keystore-encrypted at rest.", e)
        context.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
    }

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
        const val TAG = "VAM-ApiKeyStore"
        const val PREFS_NAME = "voice_access_messenger_api_key"
        const val KEY_PREF = "api_key"
    }
}
