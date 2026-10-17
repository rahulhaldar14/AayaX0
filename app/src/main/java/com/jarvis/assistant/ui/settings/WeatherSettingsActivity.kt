package com.jarvis.assistant.ui.settings

import android.Manifest
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.util.*
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class WeatherSettingsActivity : AppCompatActivity() {
    private lateinit var settings: WeatherSettingsRepository
    private lateinit var source: Spinner
    private lateinit var city: EditText
    private lateinit var apiKey: EditText
    private lateinit var deviceLocation: CheckBox
    private lateinit var units: Spinner
    private lateinit var refresh: Spinner
    private lateinit var status: TextView

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) fetchWeather(force = true)
        else status.text = "Location permission denied. Enter a city or grant permission in Android Settings."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        settings = WeatherSettingsRepository(this)
        setContentView(buildScreen())
        load()
    }

    private fun buildScreen(): View {
        val root = AyhaSettingsUi.page(this)
        AyhaSettingsUi.header(this, root, "Weather Settings", "Live conditions for your dashboard")
        val form = AyhaSettingsUi.scroll(this, root)

        AyhaSettingsUi.sectionTitle(this, form, "WEATHER SOURCE")
        AyhaSettingsUi.card(this, form) {
            source = AyhaSettingsUi.spinner(this@WeatherSettingsActivity)
            source.adapter = ArrayAdapter(this@WeatherSettingsActivity, android.R.layout.simple_spinner_item, WeatherSource.entries.map { it.label }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            AyhaSettingsUi.label(this@WeatherSettingsActivity, this, "Weather provider")
            addView(source)
            city = field("City or town", "e.g. Mumbai")
            deviceLocation = CheckBox(this@WeatherSettingsActivity).apply {
                text = "Use device location"
                textSize = 13f
                setTextColor(0xFF3D4655.toInt())
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF7C3AED.toInt())
            }
            addView(deviceLocation)
            AyhaSettingsUi.info(this@WeatherSettingsActivity, this, "Location permission is requested only when you fetch weather. Turn this off to use the city entered above.")
        }

        AyhaSettingsUi.sectionTitle(this, form, "API CREDENTIALS")
        AyhaSettingsUi.card(this, form) {
            apiKey = field("OpenWeatherMap API key", "Optional for Open-Meteo")
            apiKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            apiKey.transformationMethod = PasswordTransformationMethod.getInstance()
            addView(AyhaSettingsUi.button(this@WeatherSettingsActivity, "Show / hide key") {
                apiKey.transformationMethod = if (apiKey.transformationMethod == null) PasswordTransformationMethod.getInstance() else null
                apiKey.setSelection(apiKey.text.length)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            AyhaSettingsUi.info(this@WeatherSettingsActivity, this, "Open-Meteo needs no key for ordinary non-commercial use. OpenWeatherMap requires a key.")
        }

        AyhaSettingsUi.sectionTitle(this, form, "DISPLAY & REFRESH")
        AyhaSettingsUi.card(this, form) {
            units = spinner("Temperature units", listOf("Celsius", "Fahrenheit"))
            refresh = spinner("Refresh interval", listOf("15 minutes", "30 minutes", "60 minutes", "3 hours", "6 hours"))
        }

        AyhaSettingsUi.sectionTitle(this, form, "CURRENT STATUS")
        AyhaSettingsUi.card(this, form) {
            status = AyhaSettingsUi.status(this@WeatherSettingsActivity, this)
            addView(AyhaSettingsUi.button(this@WeatherSettingsActivity, "Test connection · Fetch live weather") {
                fetchWeather(force = true)
            })
        }
        AyhaSettingsUi.info(this, form, "Open-Meteo data requires CC BY 4.0 attribution. OpenWeatherMap is subject to its API terms.")

        root.addView(AyhaSettingsUi.button(this, "Save weather settings", primary = true) {
            save()
            Toast.makeText(this@WeatherSettingsActivity, "Weather settings saved", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return root
    }

    private fun load() {
        source.setSelection(settings.source.ordinal)
        city.setText(settings.location)
        deviceLocation.isChecked = settings.useDeviceLocation
        apiKey.setText("")
        units.setSelection(if (settings.fahrenheit) 1 else 0)
        refresh.setSelection(listOf(15, 30, 60, 180, 360).indexOf(settings.refreshMinutes).coerceAtLeast(0))
        status.text = "Last successful update: " + if (settings.lastSuccessfulUpdate > 0) DateFormat.getDateTimeInstance().format(Date(settings.lastSuccessfulUpdate)) else "Not yet fetched"
        city.isEnabled = !deviceLocation.isChecked
        deviceLocation.setOnCheckedChangeListener { _, checked -> city.isEnabled = !checked }
    }

    private fun save() {
        settings.source = WeatherSource.entries[source.selectedItemPosition]
        settings.location = city.text.toString()
        settings.useDeviceLocation = deviceLocation.isChecked
        settings.fahrenheit = units.selectedItemPosition == 1
        settings.refreshMinutes = listOf(15, 30, 60, 180, 360)[refresh.selectedItemPosition]
        if (apiKey.text.isNotBlank()) settings.apiKey = apiKey.text.toString()
        apiKey.setText("")
    }

    private fun fetchWeather(force: Boolean) {
        save()
        if (settings.useDeviceLocation && !PermissionHelper.hasLocationPermission(this)) {
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        status.text = "Fetching current weather…"
        lifecycleScope.launch {
            val result = LocationWeatherHelper.fetchWeather(this@WeatherSettingsActivity, settings, force)
            status.text = if (result == null) "Weather unavailable. Check the city, network, API key, or location permission, then retry."
            else "${result.cityName} • ${result.temperature} • ${result.condition}\n${result.details}\nUpdated ${DateFormat.getDateTimeInstance().format(Date(result.updatedAt))}"
        }
    }

    private fun LinearLayout.spinner(label: String, values: List<String>): Spinner {
        AyhaSettingsUi.label(this@WeatherSettingsActivity, this, label)
        return AyhaSettingsUi.spinner(this@WeatherSettingsActivity).also { view ->
            view.adapter = ArrayAdapter(this@WeatherSettingsActivity, android.R.layout.simple_spinner_item, values).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            addView(view)
        }
    }
    private fun LinearLayout.field(label: String, hint: String): EditText {
        AyhaSettingsUi.label(this@WeatherSettingsActivity, this, label)
        return AyhaSettingsUi.edit(this@WeatherSettingsActivity, hint).also { addView(it) }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
