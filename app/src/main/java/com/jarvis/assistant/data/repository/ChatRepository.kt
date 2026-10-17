package com.jarvis.assistant.data.repository

import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChatRepository(private val preferences: AppPreferences) {

    private val _turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val turns: StateFlow<List<ChatTurn>> = _turns.asStateFlow()

    init {
        _turns.value = preferences.loadChatHistory()
    }

    @Synchronized
    fun addTurn(userText: String, jarvisText: String) {
        val cleanUser = userText.trim()
        val cleanJarvis = jarvisText.trim()

        if (cleanUser.isEmpty() && cleanJarvis.isEmpty()) return

        val currentList = _turns.value.toMutableList()

        if (currentList.isNotEmpty()) {
            val last = currentList.last()
            if (last.userTranscript == cleanUser && last.jarvisResponse == cleanJarvis) {
                return
            }
        }

        val newTurn = ChatTurn(
            userTranscript = cleanUser.ifEmpty { "(Audio query)" },
            jarvisResponse = cleanJarvis.ifEmpty { "(Audio response)" }
        )

        currentList.add(newTurn)
        if (currentList.size > 100) {
            currentList.removeAt(0)
        }

        _turns.value = currentList
        preferences.saveChatHistory(currentList)
    }

    @Synchronized
    fun beginStreamingTurn(userText: String): String {
        val turn = ChatTurn(userTranscript = userText.trim(), jarvisResponse = "…")
        val updated = (_turns.value + turn).takeLast(100)
        _turns.value = updated
        preferences.saveChatHistory(updated)
        return turn.id
    }

    @Synchronized
    fun updateStreamingTurn(turnId: String, response: String) {
        val updated = _turns.value.map { turn ->
            if (turn.id == turnId) turn.copy(jarvisResponse = response.ifBlank { "…" }) else turn
        }
        _turns.value = updated
        preferences.saveChatHistory(updated)
    }

    @Synchronized
    fun clearHistory() {
        _turns.value = emptyList()
        preferences.clearChatHistory()
    }
}
