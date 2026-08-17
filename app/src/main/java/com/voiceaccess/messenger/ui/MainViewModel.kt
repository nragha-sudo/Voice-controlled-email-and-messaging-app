package com.voiceaccess.messenger.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voiceaccess.messenger.controller.VoiceAssistantController
import com.voiceaccess.messenger.data.MessageRepository
import com.voiceaccess.messenger.data.SourceApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MessageRepository.getInstance(application)
    val controller = VoiceAssistantController(application, repository)

    val unreadCount: StateFlow<Int> = repository.observeUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val outlookUnreadCount: StateFlow<Int> = repository.observeUnreadCount(SourceApp.OUTLOOK)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val whatsappUnreadCount: StateFlow<Int> = repository.observeUnreadCount(SourceApp.WHATSAPP)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val status: StateFlow<String> = controller.status

    /** Debug utility: wipes the entire local message_queue table (read and unread). */
    fun clearQueue() {
        viewModelScope.launch { repository.clearAll() }
    }

    override fun onCleared() {
        controller.shutdown()
        super.onCleared()
    }
}
