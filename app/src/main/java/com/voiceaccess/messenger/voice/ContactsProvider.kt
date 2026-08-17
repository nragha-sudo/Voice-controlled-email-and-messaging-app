package com.voiceaccess.messenger.voice

import android.content.Context
import android.provider.ContactsContract
import android.util.Log

/**
 * Reads the device's contact display names to use as speech-recognition
 * "biasing" hints (RecognizerIntent.EXTRA_BIASING_STRINGS) so uncommon names
 * transcribe correctly instead of getting mangled by the generic language
 * model. Entirely optional — callers should only invoke this after
 * confirming READ_CONTACTS is granted; on any failure (permission revoked
 * mid-call, no contacts provider, etc.) this degrades to an empty list
 * rather than crashing.
 */
object ContactsProvider {

    /**
     * RecognizerIntent.EXTRA_BIASING_STRINGS as a raw string constant: it's
     * only defined as a compile-time constant on newer SDKs, but the extra
     * key itself is just a string — setting it on any API level is a
     * harmless no-op for recognizers that don't look for it.
     */
    const val EXTRA_BIASING_STRINGS = "android.speech.extra.BIASING_STRINGS"

    private const val TAG = "VAM-Contacts"
    private const val MAX_BIASING_NAMES = 200

    fun loadDisplayNames(context: Context): List<String> {
        val names = LinkedHashSet<String>()
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                null,
                null,
                "${ContactsContract.Contacts.TIMES_CONTACTED} DESC",
            )
            cursor?.use {
                val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                if (nameIndex < 0) return@use
                while (it.moveToNext() && names.size < MAX_BIASING_NAMES) {
                    it.getString(nameIndex)?.takeIf(String::isNotBlank)?.let(names::add)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "loadDisplayNames: READ_CONTACTS not granted, skipping biasing", e)
            return emptyList()
        }
        Log.d(TAG, "loadDisplayNames: loaded ${names.size} contact name(s) for recognizer biasing")
        return names.toList()
    }
}
