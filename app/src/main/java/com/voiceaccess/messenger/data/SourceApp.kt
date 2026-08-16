package com.voiceaccess.messenger.data

/**
 * The two messaging surfaces this app is voice-controlling. Neither Outlook
 * nor WhatsApp exposes a public personal API this app may use, so both are
 * driven entirely through notification listening + accessibility scraping.
 */
enum class SourceApp(val packageName: String, val displayName: String) {
    OUTLOOK("com.microsoft.office.outlook", "Outlook"),
    WHATSAPP("com.whatsapp", "WhatsApp");

    companion object {
        /** WhatsApp Business ships as a separate package but behaves identically. */
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

        /** Returns the [SourceApp] whose package matches, or null if unrecognized. */
        fun fromPackageName(packageName: String): SourceApp? = when (packageName) {
            OUTLOOK.packageName -> OUTLOOK
            WHATSAPP.packageName, WHATSAPP_BUSINESS_PACKAGE -> WHATSAPP
            else -> null
        }
    }
}
