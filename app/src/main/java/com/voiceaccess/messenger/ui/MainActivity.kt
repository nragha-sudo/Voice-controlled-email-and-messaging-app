package com.voiceaccess.messenger.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.voiceaccess.messenger.R
import com.voiceaccess.messenger.accessibility.MessageAccessibilityService
import com.voiceaccess.messenger.data.SourceApp
import com.voiceaccess.messenger.databinding.ActivityMainBinding
import com.voiceaccess.messenger.server.ApiKeyStore
import com.voiceaccess.messenger.server.ApiServerService
import com.voiceaccess.messenger.voice.ContactsProvider
import kotlinx.coroutines.launch

/**
 * The app's single screen: separate Outlook, WhatsApp, and SMS read buttons
 * (each filters the queue to that source only), a tap-then-speak
 * voice-command button (covers phrases like "read my messages" / "search
 * whatsapp for invoice"), a typed search fallback for testing without a
 * microphone, and the local API server controls (start/stop, API key) that
 * let Claude query the same queue over Tailscale — see server/LocalApiServer.kt.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val apiKeyStore by lazy { ApiKeyStore(applicationContext) }

    /**
     * Every entry point that can end up listening for speech (both read
     * buttons — they listen for reply/skip/done after each message — and the
     * voice-command button) must request RECORD_AUDIO *before* starting,
     * not just the voice-command button. Without this, SpeechRecognizer
     * fails instantly instead of actually listening, which looks like "the
     * app doesn't pause to listen at all." READ_CONTACTS is requested
     * alongside it (but never blocks the action if declined) so speech
     * recognition can bias toward contact names.
     */
    private var pendingMicAction: (() -> Unit)? = null

    private val requestVoicePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val action = pendingMicAction
            pendingMicAction = null
            if (results[Manifest.permission.RECORD_AUDIO] == true) action?.invoke()
        }

    private val voiceCommandRecognizer =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) viewModel.controller.handleVoiceCommand(spoken)
        }

    /** SMS is independent of the mic/contacts flow above — it's requested once up front, same as contacts, and simply means SmsReceiver stays inert until granted. */
    private val requestSmsPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshPermissionBanner() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnReadOutlook.setOnClickListener {
            withMicPermission { viewModel.controller.readUnreadMessages(SourceApp.OUTLOOK) }
        }
        binding.btnReadWhatsapp.setOnClickListener {
            withMicPermission { viewModel.controller.readUnreadMessages(SourceApp.WHATSAPP) }
        }
        binding.btnReadSms.setOnClickListener {
            withMicPermission { viewModel.controller.readUnreadMessages(SourceApp.SMS) }
        }
        binding.btnVoiceCommand.setOnClickListener {
            withMicPermission { launchVoiceCommandRecognizer() }
        }
        binding.btnSearch.setOnClickListener {
            val keywords = binding.editSearchKeywords.text?.toString().orEmpty()
            viewModel.controller.search(app = null, keywords = keywords)
        }
        binding.textPermissionBanner.setOnClickListener { openMissingPermissionSettings() }
        binding.btnClearQueue.setOnClickListener { confirmClearQueue() }
        binding.btnStopReading.setOnClickListener { viewModel.controller.stopReading() }
        binding.btnToggleServer.setOnClickListener { toggleApiServer() }
        binding.btnCopyApiKey.setOnClickListener { copyApiKeyToClipboard() }
        binding.btnRegenerateApiKey.setOnClickListener { confirmRegenerateApiKey() }

        // Ask for the optional contacts permission once, up front, decoupled
        // from any listening action — asking it concurrently with a read/
        // voice-command button tap (as a previous version of this screen
        // did) meant a system permission dialog could pop up at the exact
        // moment SpeechRecognizer needed focus, breaking the listen.
        if (hasPermission(Manifest.permission.RECORD_AUDIO) && !hasPermission(Manifest.permission.READ_CONTACTS)) {
            requestVoicePermissions.launch(arrayOf(Manifest.permission.READ_CONTACTS))
        }

        // SMS is a whole message source (not an optional bias like contacts),
        // so it's requested unconditionally on first run rather than gated
        // behind another permission the way contacts is above. POST_NOTIFICATIONS
        // (API 33+, needed for the API server's foreground-service notification
        // to actually show) is bundled into the same one-time request.
        val missingSmsOrNotifications = buildList {
            if (!hasPermission(Manifest.permission.RECEIVE_SMS)) add(Manifest.permission.RECEIVE_SMS)
            if (!hasPermission(Manifest.permission.READ_SMS)) add(Manifest.permission.READ_SMS)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missingSmsOrNotifications.isNotEmpty()) {
            requestSmsPermissions.launch(missingSmsOrNotifications.toTypedArray())
        }

        refreshServerUi()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.unreadCount.collect {
                        binding.textUnreadCount.text = getString(R.string.label_unread_count, it)
                    }
                }
                launch {
                    viewModel.outlookUnreadCount.collect {
                        binding.textUnreadOutlook.text = getString(R.string.label_unread_count_short, it)
                    }
                }
                launch {
                    viewModel.whatsappUnreadCount.collect {
                        binding.textUnreadWhatsapp.text = getString(R.string.label_unread_count_short, it)
                    }
                }
                launch {
                    viewModel.smsUnreadCount.collect {
                        binding.textUnreadSms.text = getString(R.string.label_unread_count_short, it)
                    }
                }
                launch {
                    viewModel.status.collect { binding.textStatus.text = it }
                }
                launch {
                    viewModel.isReading.collect { reading ->
                        binding.btnStopReading.visibility = if (reading) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionBanner()
        refreshServerUi()
    }

    private fun toggleApiServer() {
        if (ApiServerService.isRunning) {
            stopService(Intent(this, ApiServerService::class.java))
        } else {
            ContextCompat.startForegroundService(this, Intent(this, ApiServerService::class.java))
        }
        // The service flips ApiServerService.isRunning synchronously in
        // onCreate/onDestroy, both of which run before start/stopService
        // returns, so this reflects the new state immediately.
        refreshServerUi()
    }

    private fun refreshServerUi() {
        val running = ApiServerService.isRunning
        binding.btnToggleServer.text = getString(
            if (running) R.string.btn_stop_server else R.string.btn_start_server,
        )
        binding.textServerStatus.text = if (running) {
            getString(R.string.status_server_running, ApiServerService.PORT)
        } else {
            getString(R.string.status_server_stopped)
        }
        binding.textApiKey.text = getString(R.string.label_api_key, apiKeyStore.getOrCreateKey())
        binding.textTailscaleHint.text = getString(R.string.label_tailscale_hint, ApiServerService.PORT)
    }

    private fun copyApiKeyToClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("API key", apiKeyStore.getOrCreateKey()))
        Toast.makeText(this, R.string.api_key_copied, Toast.LENGTH_SHORT).show()
    }

    private fun confirmRegenerateApiKey() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.api_key_regenerate_confirm_title)
            .setMessage(R.string.api_key_regenerate_confirm_message)
            .setPositiveButton(R.string.clear_queue_confirm_positive) { _, _ ->
                apiKeyStore.regenerateKey()
                refreshServerUi()
            }
            .setNegativeButton(R.string.clear_queue_confirm_negative, null)
            .show()
    }

    private fun withMicPermission(action: () -> Unit) {
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            action()
            return
        }

        pendingMicAction = action
        val permissions = if (hasPermission(Manifest.permission.READ_CONTACTS)) {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS)
        }
        requestVoicePermissions.launch(permissions)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Destructive/debug action — confirm before wiping the local queue. */
    private fun confirmClearQueue() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_queue_confirm_title)
            .setMessage(R.string.clear_queue_confirm_message)
            .setPositiveButton(R.string.clear_queue_confirm_positive) { _, _ ->
                viewModel.clearQueue()
                Toast.makeText(this, R.string.clear_queue_done, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.clear_queue_confirm_negative, null)
            .show()
    }

    private fun launchVoiceCommandRecognizer() {
        val biasingNames = if (hasPermission(Manifest.permission.READ_CONTACTS)) {
            ContactsProvider.loadDisplayNames(this)
        } else {
            emptyList()
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.btn_voice_command))
            if (biasingNames.isNotEmpty()) {
                putStringArrayListExtra(ContactsProvider.EXTRA_BIASING_STRINGS, ArrayList(biasingNames))
            }
        }
        runCatching { voiceCommandRecognizer.launch(intent) }
    }

    /** The background services need a one-time manual grant in system settings; surface whichever is missing, most-critical first. */
    private fun refreshPermissionBanner() {
        when {
            !notificationAccessGranted() -> showBanner(getString(R.string.status_notification_access_missing))
            !MessageAccessibilityService.isEnabled(this) -> showBanner(getString(R.string.status_accessibility_missing))
            !hasSmsPermission() -> showBanner(getString(R.string.status_sms_permission_missing))
            else -> binding.textPermissionBanner.visibility = View.GONE
        }
    }

    private fun hasSmsPermission(): Boolean =
        hasPermission(Manifest.permission.RECEIVE_SMS) && hasPermission(Manifest.permission.READ_SMS)

    private fun showBanner(text: String) {
        binding.textPermissionBanner.text = text
        binding.textPermissionBanner.visibility = View.VISIBLE
    }

    private fun openMissingPermissionSettings() {
        when {
            !notificationAccessGranted() -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            !MessageAccessibilityService.isEnabled(this) -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            !hasSmsPermission() -> requestSmsPermissions.launch(
                arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS),
            )
        }
    }

    private fun notificationAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
}
