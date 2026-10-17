package com.jarvis.assistant.ui.settings

import android.content.res.ColorStateList
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.ai.*
import com.jarvis.assistant.background.JarvisConversationService
import kotlinx.coroutines.launch

/** Inline provider editor used by API & Connection so credentials have one home. */
internal class ProviderSettingsPanel(private val activity: AppCompatActivity) {
    private val repository = AIProviderRepository(activity)
    private val types = AIProviderType.textProviders
    private lateinit var typeSpinner: Spinner
    private lateinit var name: EditText
    private lateinit var baseUrl: EditText
    private lateinit var key: EditText
    private lateinit var model: EditText
    private lateinit var status: TextView
    private lateinit var active: CheckBox
    private lateinit var streaming: CheckBox

    fun attach(parent: LinearLayout) {
        AyhaSettingsUi.card(activity, parent) {
            typeSpinner = AyhaSettingsUi.spinner(activity)
            typeSpinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, types.map { it.displayName }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            AyhaSettingsUi.label(activity, this, "Provider")
            addView(typeSpinner)
            name = field("Display name", "OpenRouter")
            baseUrl = field("Base URL", "https://openrouter.ai/api/v1")
            key = field("API key", "Saved key stays hidden")
            key.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            key.transformationMethod = PasswordTransformationMethod.getInstance()
            addView(AyhaSettingsUi.button(activity, "Show API key") { button ->
                val show = key.transformationMethod != null
                key.transformationMethod = if (show) null else PasswordTransformationMethod.getInstance()
                button.text = if (show) "Hide API key" else "Show API key"
                key.setSelection(key.length())
            })
            model = field("Model ID", "Discover models or enter an ID")
            streaming = CheckBox(activity).apply {
                text = "Stream responses when supported"
                textSize = 13f
                setTextColor(0xFF3D4655.toInt())
                buttonTintList = ColorStateList.valueOf(0xFF7C3AED.toInt())
            }
            addView(streaming)
            addView(AyhaSettingsUi.button(activity, "Discover available models") { runAction(true) })
            addView(AyhaSettingsUi.button(activity, "Test selected model") { runAction(false) })
            status = AyhaSettingsUi.status(activity, this)
            active = CheckBox(activity).apply {
                text = "Use this provider for typed chat across AYHA AI"
                textSize = 13f
                setTextColor(0xFF3D4655.toInt())
                buttonTintList = ColorStateList.valueOf(0xFF7C3AED.toInt())
            }
            addView(active)
            addView(AyhaSettingsUi.button(activity, "Save provider securely") { save() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(AyhaSettingsUi.button(activity, "Delete provider and key") { confirmDelete() })
            AyhaSettingsUi.info(activity, this, "Keys are encrypted with Android Keystore and masked after saving. Save first, then discover or enter a model and tap Test before activating it. Gemini Live voice above remains separate.")
        }
        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = selectType(position)
        }
        selectType(0)
    }

    private fun LinearLayout.field(label: String, hint: String): EditText {
        AyhaSettingsUi.label(activity, this, label)
        return AyhaSettingsUi.edit(activity, hint).also { addView(it) }
    }

    private fun selectType(index: Int) {
        val type = types[index.coerceIn(types.indices)]
        val config = repository.get(type)
        name.setText(config?.name ?: type.displayName)
        baseUrl.setText(config?.baseUrl ?: AIProviderRepository.defaultBase(type))
        model.setText(config?.modelId.orEmpty())
        streaming.isChecked = config?.streamingEnabled ?: true
        key.setText("")
        active.isChecked = repository.activeType() == type
        status.text = buildString {
            append(if (repository.hasKey(type)) "Credential saved: ${repository.maskedKey(type)}" else "No credential saved")
            append(if (config?.modelId.isNullOrBlank()) " • Select a model" else if (config?.validated == true) " • Model tested" else " • Not tested")
            if (repository.activeType() == type) append(" • Active")
        }
    }

    private fun config(): AIProviderConfig {
        val type = types[typeSpinner.selectedItemPosition.coerceIn(types.indices)]
        val endpoint = baseUrl.text.toString().trim().trimEnd('/')
        require(endpoint.startsWith("https://", true) && android.net.Uri.parse(endpoint).host != null) {
            "Provider endpoints must use HTTPS and include a valid host."
        }
        return AIProviderConfig(type.id, name.text.toString().trim(), endpoint, model.text.toString().trim(), streamingEnabled = streaming.isChecked)
    }

    private fun save() {
        try {
            val item = config()
            val activateWhenValidated = active.isChecked
            repository.save(item, key.text.toString(), false)
            key.setText("")
            val saved = repository.get(item.providerType)
            if (activateWhenValidated && saved?.validated == true) {
                repository.activate(item.providerType)
                JarvisConversationService.clearLiveError()
                status.text = "Provider saved securely • Active for typed chat"
            } else {
                active.isChecked = activateWhenValidated
                status.text = when {
                    item.modelId.isBlank() -> "Credential saved securely. Discover or enter a model, then test it to activate."
                    activateWhenValidated -> "Credential saved securely. Test the selected model to activate it."
                    else -> "Provider saved securely. Test the selected model before activation."
                }
            }
            Toast.makeText(activity, "Provider saved securely", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) { showError(error.message ?: "Could not save provider") }
    }

    private fun runAction(discover: Boolean) {
        val item = try { config() } catch (error: Exception) { showError(error.message ?: "Invalid endpoint"); return }
        if (!discover && item.modelId.isBlank()) { model.error = "Enter a model ID or discover models first"; return }
        try {
            repository.save(item, key.text.toString(), false)
            key.setText("")
        } catch (error: Exception) { showError(error.message ?: "Save a credential first"); return }
        status.text = if (discover) "Loading model list..." else "Testing selected model..."
        activity.lifecycleScope.launch {
            runCatching { if (discover) UnifiedAIClient(repository).discoverModels(item) else UnifiedAIClient(repository).test(item) }
                .onSuccess { result ->
                    if (discover) showModels(result as List<AIModel>) else {
                        repository.markValidated(item)
                        if (active.isChecked) {
                            repository.activate(item.providerType)
                            JarvisConversationService.clearLiveError()
                        }
                        status.text = "${result as String} • Model validated${if (active.isChecked) " • Active" else ""}"
                    }
                }.onFailure { status.text = it.message ?: "Request failed. You may enter a model ID manually." }
        }
    }

    private fun showModels(models: List<AIModel>) {
        if (models.isEmpty()) { status.text = "No models returned. Enter a model ID manually."; return }
        AlertDialog.Builder(activity).setTitle("Available models")
            .setItems(models.map { "${it.name}  (${it.id})" }.toTypedArray()) { _, position ->
                model.setText(models[position].id)
                streaming.isChecked = models[position].supportsStreaming
                status.text = "Model selected. Tap Test selected model before activation."
            }.setNegativeButton("Close", null).show()
    }

    private fun confirmDelete() {
        val type = types[typeSpinner.selectedItemPosition.coerceIn(types.indices)]
        AlertDialog.Builder(activity).setTitle("Delete ${type.displayName}?")
            .setMessage("This removes the saved configuration and encrypted API key.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                repository.delete(type)
                selectType(typeSpinner.selectedItemPosition)
            }.show()
    }

    private fun showError(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
