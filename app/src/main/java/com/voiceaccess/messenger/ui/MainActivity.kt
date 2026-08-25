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
import com.voiceaccess.messenger.controller.UserAction
import com.voiceaccess.messenger.data.SourceApp
import com.voiceaccess.messenger.databinding.ActivityMainBinding
import com.voiceaccess.messenger.server.ApiKeyStore
import com.voiceaccess.messenger.server.ApiServerService
import com.voiceaccess.messenger.voice.ContactsProvider
import kotlinx.coroutines.launch

/**
 * The app's single screen: each source (WhatsApp/Outlook/SMS) is a
 * logo-only play/pause button plus its own Search and Clear Cache buttons;
 * a Replay/Done/Reply/Skip icon row answers whatever message is currently
 * being read — by tap, exactly equivalent to saying the matching word,
 * see [com.voiceaccess.messenger.controller.VoiceAssistantController]; a
 * tap-then-speak voice-command button; and the local API server controls.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val apiKeyStore by lazy { ApiKeyStore(applicationContext) }

    /**
     * Every entry point that can end up listening for speech (the source
     * play/pause buttons — they listen for replay/done/reply/skip after
     * each message — and the voice-command button) must request
     * RECORD_AUDIO *before* starting, not just the voice-command button.
     * Without this, SpeechRecognizer fails instantly instead of actually
     * listening. READ_CONTACTS is requested alongside it (but never blocks
     * the action if declined) so speech recognition can bias toward contact
     * names.
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

        binding.btnPlayPauseWhatsapp.setOnClickListener {
            withMicPermission { viewModel.controller.togglePlayPause(SourceApp.WHATSAPP) }
        }
        binding.btnPlayPauseOutlook.setOnClickListener {
            withMicPermission { viewModel.controller.togglePlayPause(SourceApp.OUTLOOK) }
        }
        binding.btnPlayPauseSms.setOnClickListener {
            withMicPermission { viewModel.controller.togglePlayPause(SourceApp.SMS) }
        }

        binding.btnSearchWhatsapp.setOnClickListener { searchApp(SourceApp.WHATSAPP) }
        binding.btnSearchOutlook.setOnClickListener { searchApp(SourceApp.OUTLOOK) }
        binding.btnSearchSms.setOnClickListener { searchApp(SourceApp.SMS) }

        binding.btnClearCacheWhatsapp.setOnClickListener { confirmClearCache(SourceApp.WHATSAPP) }
        binding.btnClearCacheOutlook.setOnClickListener { confirmClearCache(SourceApp.OUTLOOK) }
        binding.btnClearCacheSms.setOnClickListener { confirmClearCache(SourceApp.SMS) }
        binding.btnClearCacheAll.setOnClickListener { confirmClearCache(app = null) }

        binding.btnActionReplay.setOnClickListener { viewModel.controller.submitAction(UserAction.REPLAY) }
        binding.btnActionDone.setOnClickListener { viewModel.controller.submitAction(UserAction.DONE) }
        binding.btnActionReply.setOnClickListener { viewModel.controller.submitAction(UserAction.REPLY) }
        binding.btnActionSkip.setOnClickListener { viewModel.controller.submitAction(UserAction.SKIP) }

        binding.btnVoiceCommand.setOnClickListener {
            withMicPermission { launchVoiceCommandRecognizer() }
        }
        binding.textPermissionBanner.setOnClickListener { openMissingPermissionSettings() }
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
        // behind another permission the way contacts is above. SEND_SMS (for
        // the voice/button reply action) and POST_NOTIFICATIONS (API 33+,
        // needed for the API server's foreground-service notification to
        // actually show) are bundled into the same one-time request.
        val missingSmsOrNotifications = buildList {
            if (!hasPermission(Manifest.permission.RECEIVE_SMS)) add(Manifest.permission.RECEIVE_SMS)
            if (!hasPermission(Manifest.permission.READ_SMS)) add(Manifest.permission.READ_SMS)
            if (!hasPermission(Manifest.permission.SEND_SMS)) add(Manifest.permission.SEND_SMS)
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
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionBanner()
        refreshServerUi()
    }

    private fun searchApp(app: SourceApp) {
        val keywords = binding.editSearchKeywords.text?.toString().orEmpty()
        viewModel.controller.search(app, keywords)
    }

    private fun confirmClearCache(app: SourceApp?) {
        val title = if (app == null) {
            getString(R.string.clear_cache_all_confirm_title)
        } else {
            getString(R.string.clear_cache_app_confirm_title, app.displayName)
        }
        val message = if (app == null) {
            getString(R.string.clear_cache_all_confirm_message)
        } else {
            getString(R.string.clear_cache_app_confirm_message, app.displayName)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.clear_cache_confirm_positive) { _, _ ->
                viewModel.clearCache(app)
                Toast.makeText(this, R.string.clear_cache_done, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.clear_cache_confirm_negative, null)
            .show()
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
            .setPositiveButton(R.string.clear_cache_confirm_positive) { _, _ ->
                apiKeyStore.regenerateKey()
                refreshServerUi()
            }
            .setNegativeButton(R.string.clear_cache_confirm_negative, null)
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
