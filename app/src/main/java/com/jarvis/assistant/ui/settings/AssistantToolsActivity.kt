package com.jarvis.assistant.ui.settings

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.command.AppShortcut
import com.jarvis.assistant.automation.command.AssistantToolsStore
import com.jarvis.assistant.automation.command.JarvisCommand
import com.jarvis.assistant.automation.command.ReminderScheduler
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.launch
import java.util.Calendar

class AssistantToolsActivity : AppCompatActivity() {
    private val app get() = JarvisApp.instance
    private val store by lazy { AssistantToolsStore(this) }
    private lateinit var content: LinearLayout
    private lateinit var sectionContent: LinearLayout
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val root = AyhaSettingsUi.page(this)
        AyhaSettingsUi.header(this, root, "Assistant Tools", "Routines, reminders, shortcuts and privacy")
        content = AyhaSettingsUi.scroll(this, root)
        setContentView(root)
        buildScreen()
        requestNotificationPermissionIfNeeded()
    }

    private fun buildScreen() {
        content.removeAllViews()
        section("Routines", "Run saved command sequences by voice, for example: run routine morning.")
        action("Create routine", primary = true) { createRoutine() }
        store.routines().forEach { routine ->
            action("${routine.name}  ·  ${routine.steps.size} actions  ·  Run") {
                lifecycleScope.launch {
                    val result = app.commandExecutor.execute(JarvisCommand.RunRoutine(routine.name))
                    toast(result)
                }
            }.setOnLongClickListener {
                MaterialAlertDialogBuilder(this).setMessage("Delete ${routine.name}?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ -> store.deleteRoutine(routine.name); buildScreen() }
                    .show()
                true
            }
        }

        section("Reminders", "Set a reminder notification for a date and time.")
        action("Add reminder", primary = true) { createReminder() }
        action("Voice reminder: remind me in 10 minutes to stretch") {
            toast("Try: ‘remind me in 10 minutes to stretch’ or ‘mujhe 1 ghante baad yaad dilana ki paani peena’.")
        }

        section("App shortcuts", "Save a phrase and the app to open. Say the phrase to launch it.")
        action("Create app shortcut", primary = true) { createShortcut() }
        store.shortcuts().forEach { shortcut ->
            action("${shortcut.phrase}  →  ${shortcut.appName}") {
                lifecycleScope.launch {
                    toast(app.commandExecutor.execute(JarvisCommand.OpenApp(shortcut.appName)))
                }
            }.setOnLongClickListener {
                MaterialAlertDialogBuilder(this).setMessage("Remove shortcut ‘${shortcut.phrase}’?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove") { _, _ -> removeShortcut(shortcut); buildScreen() }
                    .show()
                true
            }
        }

        section("Language", "Select Hinglish for Hindi-English mixed conversation.")
        action("Conversation language: ${app.preferences.language}") {
            val options = GeminiConstants.SUPPORTED_LANGUAGES.toTypedArray()
            MaterialAlertDialogBuilder(this).setTitle("Conversation language")
                .setSingleChoiceItems(options, options.indexOf(app.preferences.language).coerceAtLeast(0)) { dialog, which ->
                    app.preferences.language = options[which]
                    dialog.dismiss()
                    buildScreen()
                }.setNegativeButton("Cancel", null).show()
        }

        section("Confirmations", "Ask before placing calls or sending/deleting WhatsApp messages.")
        switch("Confirm sensitive actions", store.confirmSensitiveActions) { store.confirmSensitiveActions = it }

        section("Privacy", "Control microphone, screen sharing and stored conversation data.")
        switch("Microphone muted", app.preferences.isMicMuted) {
            app.preferences.isMicMuted = it
            com.jarvis.assistant.background.JarvisConversationService.setMicMuted(it)
        }
        action("Stop screen sharing now") {
            com.jarvis.assistant.background.JarvisConversationService.stopScreenCapture()
            toast("Screen sharing stopped.")
        }
        action("Review saved memories") { startActivity(android.content.Intent(this, com.jarvis.assistant.ui.memory.MemoryActivity::class.java)) }
        action("Clear chat history") {
            confirm("Clear all saved chat history?", "Clear history") {
                app.chatRepository.clearHistory()
                app.preferences.clearChatHistory()
                toast("Chat history cleared.")
            }
        }
        action("Clear learned memories") {
            confirm("Delete all learned memories?", "Delete memories") {
                lifecycleScope.launch {
                    app.memoryRepository.clearAll()
                    app.memoryManager.clearShortTermMemory()
                    toast("Learned memories cleared.")
                }
            }
        }

        section("Offline quick actions", "These controls run on the phone without a Gemini connection.")
        action("Turn flashlight on") { runQuick(JarvisCommand.Flash(true)) }
        action("Turn flashlight off") { runQuick(JarvisCommand.Flash(false)) }
        action("Open an app") {
            prompt("App name", "YouTube") { name -> runQuick(JarvisCommand.OpenApp(name)) }
        }
        action("Volume up") { runQuick(JarvisCommand.Volume(0, isRelative = true, delta = 1)) }
        action("Volume down") { runQuick(JarvisCommand.Volume(0, isRelative = true, delta = -1)) }
    }

    private fun createRoutine() {
        val name = EditText(this).apply { hint = "Routine name, e.g. morning" }
        val steps = EditText(this).apply {
            hint = "One action per line, e.g. open calendar"
            minLines = 3
            gravity = Gravity.TOP
        }
        val form = form(name, steps)
        MaterialAlertDialogBuilder(this).setTitle("New routine").setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val lines = steps.text.toString().lines().map(String::trim).filter(String::isNotBlank)
                if (name.text.isNotBlank() && lines.isNotEmpty()) {
                    store.saveRoutine(name.text.toString(), lines)
                    buildScreen()
                } else toast("Add a name and at least one action.")
            }.show()
    }

    private fun createShortcut() {
        val phrase = EditText(this).apply { hint = "Voice phrase, e.g. start my study app" }
        val appName = EditText(this).apply { hint = "App name, e.g. YouTube" }
        MaterialAlertDialogBuilder(this).setTitle("New app shortcut").setView(form(phrase, appName))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                if (phrase.text.isNotBlank() && appName.text.isNotBlank()) {
                    store.saveShortcut(phrase.text.toString(), appName.text.toString())
                    buildScreen()
                } else toast("Enter both a phrase and an app name.")
            }.show()
    }

    private fun createReminder() {
        val now = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                val whenToRemind = Calendar.getInstance().apply {
                    set(year, month, day, hour, minute, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                prompt("What should AYHA remind you about?", "Take a break") { message ->
                    if (whenToRemind.timeInMillis <= System.currentTimeMillis()) {
                        toast("Choose a future time.")
                    } else {
                        ReminderScheduler.schedule(this, whenToRemind.timeInMillis, message)
                        toast("Reminder scheduled.")
                    }
                }
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH))
            .apply { datePicker.minDate = System.currentTimeMillis() }.show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun removeShortcut(shortcut: AppShortcut) = store.deleteShortcut(shortcut.phrase)

    private fun runQuick(command: JarvisCommand) {
        lifecycleScope.launch {
            runCatching { app.commandExecutor.execute(command) }
                .onSuccess(::toast)
                .onFailure { toast(it.message ?: "Action failed.") }
        }
    }

    private fun confirm(message: String, positiveText: String, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(this).setMessage(message).setNegativeButton("Cancel", null)
            .setPositiveButton(positiveText) { _, _ -> onConfirm() }.show()
    }

    private fun prompt(title: String, placeholder: String, onSubmit: (String) -> Unit) {
        val input = EditText(this).apply { hint = placeholder }
        MaterialAlertDialogBuilder(this).setTitle(title).setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ -> input.text.toString().trim().takeIf(String::isNotBlank)?.let(onSubmit) }
            .show()
    }

    private fun form(vararg fields: EditText): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(4))
        fields.forEach { addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
    }

    private fun section(title: String, detail: String) {
        AyhaSettingsUi.sectionTitle(this, content, title.uppercase())
        sectionContent = AyhaSettingsUi.card(this, content) {
            AyhaSettingsUi.info(this@AssistantToolsActivity, this, detail)
        }
    }

    private fun action(label: String, primary: Boolean = false, action: () -> Unit): Button {
        return AyhaSettingsUi.button(this, label, primary) { action() }.also { button ->
            sectionContent.addView(button, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(8)
            })
        }
    }

    private fun switch(label: String, checked: Boolean, changed: (Boolean) -> Unit) {
        sectionContent.addView(Switch(this).apply {
            text = label
            isChecked = checked
            textSize = 14f
            setTextColor(Color.parseColor("#3D4655"))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#7C3AED"))
            setOnCheckedChangeListener { _, value -> changed(value) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
