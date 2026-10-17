package com.jarvis.assistant.automation.command

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AssistantRoutine(val name: String, val steps: List<String>)
data class AppShortcut(val phrase: String, val appName: String)

class AssistantToolsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("assistant_tools", Context.MODE_PRIVATE)

    fun routines(): List<AssistantRoutine> = readArray("routines").mapNotNull {
        runCatching {
            AssistantRoutine(it.getString("name"), it.getJSONArray("steps").let { steps ->
                List(steps.length()) { index -> steps.getString(index) }
            })
        }.getOrNull()
    }

    fun saveRoutine(name: String, steps: List<String>) {
        val all = routines().filterNot { it.name.equals(name, true) }.toMutableList()
        all.add(AssistantRoutine(name.trim(), steps.map(String::trim).filter(String::isNotBlank)))
        val array = JSONArray()
        all.forEach { routine ->
            array.put(JSONObject().put("name", routine.name).put("steps", JSONArray(routine.steps)))
        }
        prefs.edit().putString("routines", array.toString()).apply()
    }

    fun deleteRoutine(name: String) = writeRoutines(routines().filterNot { it.name == name })

    fun routine(name: String): AssistantRoutine? = routines().firstOrNull { it.name.equals(name.trim(), true) }

    fun shortcuts(): List<AppShortcut> = readArray("shortcuts").mapNotNull {
        runCatching { AppShortcut(it.getString("phrase"), it.getString("app")) }.getOrNull()
    }

    fun saveShortcut(phrase: String, appName: String) {
        val all = shortcuts().filterNot { it.phrase.equals(phrase, true) }.toMutableList()
        all.add(AppShortcut(phrase.trim(), appName.trim()))
        val array = JSONArray()
        all.forEach { array.put(JSONObject().put("phrase", it.phrase).put("app", it.appName)) }
        prefs.edit().putString("shortcuts", array.toString()).apply()
    }

    fun shortcut(phrase: String): AppShortcut? {
        val clean = phrase.trim()
            .replace(Regex("(?i)^(hey jarvis|hi jarvis|hello jarvis|ok jarvis|jarvis|service)[,\\s]*"), "")
            .trimEnd('.', '!', '?', ',')
        return shortcuts().firstOrNull { it.phrase.equals(clean, true) }
    }

    fun deleteShortcut(phrase: String) {
        val array = JSONArray()
        shortcuts().filterNot { it.phrase.equals(phrase, true) }
            .forEach { array.put(JSONObject().put("phrase", it.phrase).put("app", it.appName)) }
        prefs.edit().putString("shortcuts", array.toString()).apply()
    }

    var confirmSensitiveActions: Boolean
        get() = prefs.getBoolean("confirm_sensitive_actions", true)
        set(value) { prefs.edit().putBoolean("confirm_sensitive_actions", value).apply() }

    private fun readArray(key: String): List<JSONObject> = runCatching {
        val data = JSONArray(prefs.getString(key, "[]"))
        List(data.length()) { data.getJSONObject(it) }
    }.getOrDefault(emptyList())

    private fun writeRoutines(items: List<AssistantRoutine>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("name", it.name).put("steps", JSONArray(it.steps))) }
        prefs.edit().putString("routines", array.toString()).apply()
    }
}
