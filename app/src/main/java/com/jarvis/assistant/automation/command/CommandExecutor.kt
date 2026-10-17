package com.jarvis.assistant.automation.command

import android.util.Log
import android.content.Context
import com.jarvis.assistant.automation.phone.PhoneControlManager
import com.jarvis.assistant.automation.whatsapp.WhatsAppAssistant
import com.jarvis.assistant.automation.youtube.YouTubeAutomationManager
import com.jarvis.assistant.data.memory.MemoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CommandExecutor(
    private val context: Context,
    private val phoneManager: PhoneControlManager,
    private val youtubeManager: YouTubeAutomationManager,
    private val whatsappAssistant: WhatsAppAssistant,
    private val memoryManager: MemoryManager
) {
    companion object {
        private const val TAG = "CommandExecutor"
    }

    suspend fun execute(command: JarvisCommand): String = withContext(Dispatchers.Main) {
        Log.i(TAG, "Executing command: $command")
        when (command) {
            // YouTube
            is JarvisCommand.PlayYouTube -> {
                val resolvedSong = memoryManager.resolveSongQuery(command.query)
                memoryManager.trackSongPlay(resolvedSong)
                youtubeManager.playVideo(resolvedSong)
            }
            is JarvisCommand.SearchYouTube -> youtubeManager.searchYouTube(command.query)
            is JarvisCommand.PauseMedia -> youtubeManager.pausePlayback()
            is JarvisCommand.ResumeMedia -> youtubeManager.resumePlayback()
            is JarvisCommand.NextMedia -> youtubeManager.nextMedia()
            is JarvisCommand.PreviousMedia -> youtubeManager.previousMedia()
            is JarvisCommand.SubscribeChannel -> youtubeManager.subscribeToChannel()
            is JarvisCommand.ToggleFullscreen -> youtubeManager.toggleFullscreen()

            // Phone Controls
            is JarvisCommand.Flash -> phoneManager.setFlashlight(command.enabled)
            is JarvisCommand.Brightness -> {
                if (command.isRelative) {
                    phoneManager.adjustBrightness(command.delta > 0)
                } else {
                    phoneManager.setBrightnessPercent(command.level)
                }
            }
            is JarvisCommand.Volume -> {
                if (command.isRelative) {
                    phoneManager.adjustVolume(command.delta > 0)
                } else {
                    phoneManager.setVolumePercent(command.level)
                }
            }
            is JarvisCommand.OpenApp -> phoneManager.openApp(command.packageName)
            is JarvisCommand.OpenWebUrls -> phoneManager.openMultipleUrls(command.urls)
            is JarvisCommand.PhoneCall -> phoneManager.makePhoneCall(command.contactOrNumber)
            is JarvisCommand.SearchWeb -> phoneManager.searchWeb(command.query)

            // WhatsApp
            is JarvisCommand.WhatsAppMessage -> whatsappAssistant.sendMessageDirectly(command.contact, command.message)
            is JarvisCommand.WhatsAppCall -> whatsappAssistant.makeCall(command.contact, command.video)
            is JarvisCommand.WhatsAppDeleteMessage -> whatsappAssistant.deleteLastMessage(command.contact, command.forEveryone)
            is JarvisCommand.ConfirmAction -> {
                if (command.confirmed) {
                    whatsappAssistant.confirmAndSend()
                } else {
                    whatsappAssistant.cancelDraft()
                }
            }
            is JarvisCommand.DisambiguationChoice -> whatsappAssistant.handleDisambiguationChoice(command.choice)
            is JarvisCommand.RunRoutine -> {
                val routine = AssistantToolsStore(context).routine(command.name)
                    ?: return@withContext "I couldn't find a routine named ${command.name}."
                val results = routine.steps.mapNotNull { step ->
                    val parsed = CommandParser.parse(step).command ?: return@mapNotNull null
                    if (parsed !is JarvisCommand.OpenApp && parsed !is JarvisCommand.Flash &&
                        parsed !is JarvisCommand.Brightness && parsed !is JarvisCommand.Volume &&
                        parsed !is JarvisCommand.OpenWebUrls && parsed !is JarvisCommand.SearchWeb &&
                        parsed !is JarvisCommand.PlayYouTube && parsed !is JarvisCommand.PauseMedia &&
                        parsed !is JarvisCommand.ResumeMedia && parsed !is JarvisCommand.SetReminder
                    ) return@mapNotNull null
                    execute(parsed)
                }
                if (results.isEmpty()) "Routine ${routine.name} has no runnable actions." else results.joinToString(" ")
            }
            is JarvisCommand.SetReminder -> {
                val time = System.currentTimeMillis() + command.delayMinutes * 60_000L
                ReminderScheduler.schedule(context, time, command.text)
                "Reminder set for ${command.delayMinutes} minutes from now."
            }

            // Memory
            is JarvisCommand.Remember -> memoryManager.rememberFact(command.text)
            is JarvisCommand.Forget -> memoryManager.forgetFact(command.query)
            is JarvisCommand.WhatDoYouRemember -> memoryManager.getWhatDoYouRemember()
            is JarvisCommand.RecallBirthday -> memoryManager.getBirthdayResponse()
            is JarvisCommand.RecallFavoriteSong -> memoryManager.getFavoriteSongResponse()
            is JarvisCommand.RecallRoutine -> memoryManager.getRoutineResponse()
            is JarvisCommand.GoToBackground -> "Going to background."
        }
    }
}
