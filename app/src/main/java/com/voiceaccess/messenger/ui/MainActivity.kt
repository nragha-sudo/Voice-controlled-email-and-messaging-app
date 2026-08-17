package com.voiceaccess.messenger.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.voiceaccess.messenger.R
import com.voiceaccess.messenger.accessibility.MessageAccessibilityService
import com.voiceaccess.messenger.data.SourceApp
import com.voiceaccess.messenger.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * The app's single screen: separate Outlook and WhatsApp read buttons (each
 * filters the queue to that app only), a tap-then-speak voice-command button
 * (covers phrases like "read my messages" / "search whatsapp for invoice"),
 * and a typed search fallback for testing without a microphone.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    /**
     * Every entry point that can end up listening for speech (both read
     * buttons — they listen for reply/skip/done after each message — and the
     * voice-command button) must request RECORD_AUDIO *before* starting,
     * not just the voice-command button. Without this, SpeechRecognizer
     * fails instantly instead of actually listening, which looks like "the
     * app doesn't pause to listen at all."
     */
    private var pendingMicAction: (() -> Unit)? = null

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingMicAction
            pendingMicAction = null
            if (granted) action?.invoke()
        }

    private val voiceCommandRecognizer =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) viewModel.controller.handleVoiceCommand(spoken)
        }

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
        binding.btnVoiceCommand.setOnClickListener {
            withMicPermission { launchVoiceCommandRecognizer() }
        }
        binding.btnSearch.setOnClickListener {
            val keywords = binding.editSearchKeywords.text?.toString().orEmpty()
            viewModel.controller.search(app = null, keywords = keywords)
        }
        binding.textPermissionBanner.setOnClickListener { openMissingPermissionSettings() }

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
                    viewModel.status.collect { binding.textStatus.text = it }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionBanner()
    }

    private fun withMicPermission(action: () -> Unit) {
        val hasMicPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (hasMicPermission) {
            action()
        } else {
            pendingMicAction = action
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchVoiceCommandRecognizer() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.btn_voice_command))
        }
        runCatching { voiceCommandRecognizer.launch(intent) }
    }

    /** Both background services need a one-time manual grant in system settings; surface whichever is missing. */
    private fun refreshPermissionBanner() {
        when {
            !notificationAccessGranted() -> showBanner(getString(R.string.status_notification_access_missing))
            !MessageAccessibilityService.isEnabled(this) -> showBanner(getString(R.string.status_accessibility_missing))
            else -> binding.textPermissionBanner.visibility = View.GONE
        }
    }

    private fun showBanner(text: String) {
        binding.textPermissionBanner.text = text
        binding.textPermissionBanner.visibility = View.VISIBLE
    }

    private fun openMissingPermissionSettings() {
        val action = if (!notificationAccessGranted()) {
            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
        } else {
            Settings.ACTION_ACCESSIBILITY_SETTINGS
        }
        startActivity(Intent(action))
    }

    private fun notificationAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
}
