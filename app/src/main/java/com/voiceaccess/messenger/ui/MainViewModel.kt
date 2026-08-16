package com.voiceaccess.messenger.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voiceaccess.messenger.controller.VoiceAssistantController
import com.voiceaccess.messenger.data.MessageRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MessageRepository.getInstance(application)
    val controller = VoiceAssistantController(application, repository)

    val unreadCount: StateFlow<Int> = repository.observeUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val status: StateFlow<String> = controller.status

    override fun onCleared() {
        controller.shutdown()
        super.onCleared()
    }
}
