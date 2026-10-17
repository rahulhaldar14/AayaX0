package com.jarvis.assistant.ai

enum class AIProviderType(val id: String, val displayName: String) {
    OPENAI("openai", "OpenAI"),
    OPENROUTER("openrouter", "OpenRouter"),
    DEEPSEEK("deepseek", "DeepSeek"),
    GEMINI("gemini", "Google Gemini"),
    CUSTOM("custom", "Custom OpenAI-compatible"),
    GEMINI_LIVE("gemini_live", "Gemini Live (voice)");

    companion object {
        fun fromId(id: String): AIProviderType = entries.firstOrNull { it.id == id } ?: GEMINI_LIVE
        val textProviders = listOf(OPENAI, OPENROUTER, DEEPSEEK, GEMINI, CUSTOM)
    }
}

data class AIProviderConfig(
    val type: String,
    val name: String,
    val baseUrl: String,
    val modelId: String,
    val validated: Boolean = false,
    val streamingEnabled: Boolean = true
) {
    val providerType: AIProviderType get() = AIProviderType.fromId(type)
}

data class AIModel(val id: String, val name: String = id, val supportsStreaming: Boolean = true)
