package com.jarvis.assistant.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class AIProviderRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("ayha_provider_configs", Context.MODE_PRIVATE)
    private val secrets: SharedPreferences = run {
        val masterKey = MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            appContext,
            "ayha_provider_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    private val gson = Gson()

    companion object {
        private const val KEY_CONFIGS = "configs"
        private const val KEY_ACTIVE = "active_provider"
        const val ACTIVE_LIVE = "gemini_live"
        fun defaultBase(type: AIProviderType): String = when (type) {
            AIProviderType.OPENAI -> "https://api.openai.com/v1"
            AIProviderType.OPENROUTER -> "https://openrouter.ai/api/v1"
            AIProviderType.DEEPSEEK -> "https://api.deepseek.com/v1"
            AIProviderType.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
            AIProviderType.CUSTOM -> ""
            AIProviderType.GEMINI_LIVE -> ""
        }
    }

    fun all(): List<AIProviderConfig> {
        val json = prefs.getString(KEY_CONFIGS, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<AIProviderConfig>>(json, object : TypeToken<List<AIProviderConfig>>() {}.type).orEmpty()
        }.getOrDefault(emptyList())
    }

    fun get(type: AIProviderType): AIProviderConfig? = all().firstOrNull { it.type == type.id }
    fun apiKey(type: AIProviderType): String = secrets.getString(secretKey(type), "").orEmpty()
    fun hasKey(type: AIProviderType): Boolean = apiKey(type).isNotBlank()
    fun maskedKey(type: AIProviderType): String = apiKey(type).takeLast(4).takeIf { it.isNotBlank() }?.let { "•••• $it" }.orEmpty()
    fun activeType(): AIProviderType = AIProviderType.fromId(prefs.getString(KEY_ACTIVE, ACTIVE_LIVE).orEmpty())

    fun save(config: AIProviderConfig, apiKey: String, makeActive: Boolean) {
        require(config.providerType in AIProviderType.textProviders)
        val cleanKey = apiKey.trim()
        require(cleanKey.isNotEmpty() || hasKey(config.providerType)) { "Enter an API key before saving this provider." }
        if (cleanKey.isNotEmpty()) secrets.edit().putString(secretKey(config.providerType), cleanKey).commit()
        val old = get(config.providerType)
        val keepValidation = old?.validated == true && cleanKey.isEmpty() &&
            old.baseUrl == config.baseUrl.trim().trimEnd('/') && old.modelId == config.modelId.trim()
        val updated = all().filterNot { it.type == config.type } + config.copy(
            name = config.name.trim().ifBlank { config.providerType.displayName },
            baseUrl = config.baseUrl.trim().trimEnd('/'),
            modelId = config.modelId.trim(),
            validated = keepValidation
        )
        check(prefs.edit().putString(KEY_CONFIGS, gson.toJson(updated)).commit()) { "Could not save provider settings." }
        if (makeActive) activate(config.providerType)
    }

    fun activate(type: AIProviderType) {
        require(type == AIProviderType.GEMINI_LIVE || get(type)?.validated == true) { "Test this provider and model successfully before activating it." }
        prefs.edit().putString(KEY_ACTIVE, type.id).apply()
    }

    fun markValidated(config: AIProviderConfig) {
        val saved = get(config.providerType) ?: error("Save this provider before testing its model.")
        require(saved.baseUrl == config.baseUrl.trim().trimEnd('/') && saved.modelId == config.modelId.trim()) {
            "Configuration changed during test. Save and test the selected model again."
        }
        val updated = saved.copy(validated = true)
        prefs.edit().putString(KEY_CONFIGS, gson.toJson(all().map { if (it.type == updated.type) updated else it })).apply()
    }

    fun delete(type: AIProviderType) {
        prefs.edit().putString(KEY_CONFIGS, gson.toJson(all().filterNot { it.type == type.id })).apply()
        check(secrets.edit().remove(secretKey(type)).commit()) { "Could not remove the saved credential." }
        if (activeType() == type) activate(AIProviderType.GEMINI_LIVE)
    }

    private fun secretKey(type: AIProviderType) = "provider_key_${type.id}"
}
