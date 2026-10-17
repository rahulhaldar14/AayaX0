package com.jarvis.assistant.ui.settings

import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.ai.*
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.launch

class AIProvidersActivity : AppCompatActivity() {
    private lateinit var repository: AIProviderRepository
    private lateinit var typeSpinner: Spinner
    private lateinit var name: EditText
    private lateinit var baseUrl: EditText
    private lateinit var key: EditText
    private lateinit var model: EditText
    private lateinit var status: TextView
    private lateinit var active: CheckBox
    private lateinit var streaming: CheckBox
    private val types = AIProviderType.textProviders

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        startActivity(android.content.Intent(this, ApiConnectionActivity::class.java))
        finish()
    }

    private fun buildScreen(): View {
        val root = AyhaSettingsUi.page(this)
        AyhaSettingsUi.header(this, root, "AI Providers", "Text chat models • AYHA AI")
        val form = AyhaSettingsUi.scroll(this, root)

        AyhaSettingsUi.sectionTitle(this, form, "PROVIDER")
        AyhaSettingsUi.card(this, form) {
            typeSpinner = AyhaSettingsUi.spinner(this@AIProvidersActivity)
            typeSpinner.adapter = ArrayAdapter(this@AIProvidersActivity, android.R.layout.simple_spinner_item, types.map { it.displayName }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            AyhaSettingsUi.label(this@AIProvidersActivity, this, "Provider")
            addView(typeSpinner)
            name = field("Display name", "OpenAI")
            baseUrl = field("Base URL", "https://api.example.com/v1")
        }

        AyhaSettingsUi.sectionTitle(this, form, "API CREDENTIALS")
        AyhaSettingsUi.card(this, form) {
            key = field("API key", "Saved key stays hidden")
            key.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            key.transformationMethod = PasswordTransformationMethod.getInstance()
            addView(AyhaSettingsUi.button(this@AIProvidersActivity, "Show API key") {
                val show = key.transformationMethod != null
                key.transformationMethod = if (show) null else PasswordTransformationMethod.getInstance()
                (it as Button).text = if (show) "Hide API key" else "Show API key"
                key.setSelection(key.text.length)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        AyhaSettingsUi.sectionTitle(this, form, "MODEL ENGINE")
        AyhaSettingsUi.card(this, form) {
            model = field("Model ID", "Discover models or enter an ID")
            streaming = CheckBox(this@AIProvidersActivity).apply {
                text = "Stream responses when supported"
                textSize = 13f
                setTextColor(0xFF3D4655.toInt())
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF7C3AED.toInt())
            }
            addView(streaming)
            addView(AyhaSettingsUi.button(this@AIProvidersActivity, "Discover available models") {
                runProviderAction(discover = true)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(AyhaSettingsUi.button(this@AIProvidersActivity, "Test selected model") {
                runProviderAction(discover = false)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        AyhaSettingsUi.sectionTitle(this, form, "CONNECTION STATUS")
        AyhaSettingsUi.card(this, form) {
            status = AyhaSettingsUi.status(this@AIProvidersActivity, this)
        }

        AyhaSettingsUi.sectionTitle(this, form, "ACTIVE PROVIDER")
        AyhaSettingsUi.card(this, form) {
            active = CheckBox(this@AIProvidersActivity).apply {
                text = "Use for typed chat across AYHA AI"
                textSize = 13f
                setTextColor(0xFF3D4655.toInt())
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF7C3AED.toInt())
            }
            addView(active)
            addView(AyhaSettingsUi.button(this@AIProvidersActivity, "Delete provider and key") { confirmDelete() })
            AyhaSettingsUi.info(this@AIProvidersActivity, this, "Only saved and successfully tested configurations can be activated. Gemini Live voice remains separate in API & Connection.")
        }
        AyhaSettingsUi.info(this, form, "Credentials are encrypted with Android Keystore and masked after saving. A mobile APK cannot fully protect a key from a determined attacker; a backend proxy is recommended for production. A model test sends one short request only when you tap Test.")

        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = selectType(position)
        }

        root.addView(AyhaSettingsUi.button(this, "Save configuration", primary = true) { save() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return root
    }

    private fun LinearLayout.field(label: String, hint: String): EditText {
        AyhaSettingsUi.label(this@AIProvidersActivity, this, label)
        return AyhaSettingsUi.edit(this@AIProvidersActivity, hint).also { addView(it) }
    }

    private fun selectType(index: Int) {
        if (!::repository.isInitialized || !::name.isInitialized) return
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
            append(if (config?.validated == true) " • Model tested" else " • Not tested")
            if (repository.activeType() == type) append(" • Active")
            if (type == AIProviderType.CUSTOM) append(" • Use a base URL compatible with OpenAI chat completions")
        }
    }

    private fun currentConfig(): AIProviderConfig {
        val type = types[typeSpinner.selectedItemPosition.coerceIn(types.indices)]
        val endpoint = baseUrl.text.toString().trim().trimEnd('/')
        require(endpoint.startsWith("https://", true) && android.net.Uri.parse(endpoint).host != null) {
            "Provider endpoints must use HTTPS and include a valid host."
        }
        if (type == AIProviderType.CUSTOM) require(endpoint.startsWith("https://", true) && android.net.Uri.parse(endpoint).host != null) {
            "Custom endpoints must use HTTPS and include a valid host."
        }
        return AIProviderConfig(type.id, name.text.toString(), endpoint, model.text.toString(), streamingEnabled = streaming.isChecked)
    }

    private fun save() {
        try {
            val config = currentConfig()
            if (config.modelId.isBlank()) { model.error = "Enter a model ID or discover models first"; return }
            repository.save(config, key.text.toString(), false)
            if (active.isChecked) repository.activate(config.providerType)
            key.setText("")
            Toast.makeText(this, "Provider saved securely", Toast.LENGTH_SHORT).show()
            selectType(typeSpinner.selectedItemPosition)
        } catch (e: Exception) { showError(e.message ?: "Could not save provider") }
    }

    private fun runProviderAction(discover: Boolean) {
        val config = try { currentConfig() } catch (e: Exception) { showError(e.message ?: "Invalid endpoint"); return }
        try {
            repository.save(config, key.text.toString(), false)
            key.setText("")
        } catch (e: Exception) { showError(e.message ?: "Save a credential first"); return }
        val client = UnifiedAIClient(repository)
        status.text = if (discover) "Loading model list…" else "Testing selected model…"
        lifecycleScope.launch {
            runCatching { if (discover) client.discoverModels(config) else client.test(config) }
                .onSuccess { result ->
                    if (discover) {
                        @Suppress("UNCHECKED_CAST")
                        val models = result as List<AIModel>
                        if (models.isEmpty()) status.text = "No models returned. Enter a model ID manually."
                        else AlertDialog.Builder(this@AIProvidersActivity)
                            .setTitle("Available models")
                            .setItems(models.map { "${it.name}  (${it.id})" }.toTypedArray()) { _, which ->
                                model.setText(models[which].id)
                                streaming.isChecked = models[which].supportsStreaming
                                save()
                            }.setNegativeButton("Close", null).show()
                    } else {
                        repository.markValidated(config)
                        if (active.isChecked) repository.activate(config.providerType)
                        status.text = "${result as String} • Model validated${if (active.isChecked) " • Active" else ""}"
                    }
                }
                .onFailure { status.text = it.message ?: "Request failed" }
        }
    }

    private fun confirmDelete() {
        val type = types[typeSpinner.selectedItemPosition.coerceIn(types.indices)]
        AlertDialog.Builder(this).setTitle("Delete ${type.displayName}?")
            .setMessage("This removes the saved configuration and encrypted API key.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                repository.delete(type); selectType(typeSpinner.selectedItemPosition)
            }.show()
    }

    private fun showError(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
