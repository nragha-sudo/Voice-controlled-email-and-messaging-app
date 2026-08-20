package com.voiceaccess.messenger.data

/**
 * The messaging surfaces this app is voice-controlling. Outlook and WhatsApp
 * expose no public personal API this app may use, so both are driven
 * entirely through notification listening + accessibility scraping. SMS is
 * a third, platform-level source: it arrives via [android.provider.Telephony]
 * broadcasts instead of a notification, so it has no installed-app package
 * of its own — [packageName] is a synthetic id, never matched against a real
 * package (see [fromPackageName], which SMS deliberately does not appear in).
 */
enum class SourceApp(val packageName: String, val displayName: String) {
    OUTLOOK("com.microsoft.office.outlook", "Outlook"),
    WHATSAPP("com.whatsapp", "WhatsApp"),
    SMS("sms", "SMS");

    companion object {
        /** WhatsApp Business ships as a separate package but behaves identically. */
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

        /** Returns the [SourceApp] whose package matches, or null if unrecognized. Used only for notification routing, so SMS (no notification-listener package) is intentionally never matched here. */
        fun fromPackageName(packageName: String): SourceApp? = when (packageName) {
            OUTLOOK.packageName -> OUTLOOK
            WHATSAPP.packageName, WHATSAPP_BUSINESS_PACKAGE -> WHATSAPP
            else -> null
        }
    }
}
