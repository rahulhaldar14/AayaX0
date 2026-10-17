package com.jarvis.assistant.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class WeatherSource(val label: String) { OPEN_METEO("Open-Meteo (free)"), OPEN_WEATHER_MAP("OpenWeatherMap") }

class WeatherSettingsRepository(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("ayha_weather_settings", Context.MODE_PRIVATE)
    private val secure by lazy {
        val master = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            app, "ayha_weather_secret", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var source: WeatherSource
        get() = runCatching { WeatherSource.valueOf(prefs.getString("source", WeatherSource.OPEN_METEO.name)!!) }
            .getOrDefault(WeatherSource.OPEN_METEO)
        set(value) { prefs.edit().putString("source", value.name).apply() }
    var location: String
        get() = prefs.getString("location", "")?.trim().orEmpty()
        set(value) { prefs.edit().putString("location", value.trim()).apply() }
    var useDeviceLocation: Boolean
        get() = prefs.getBoolean("device_location", true)
        set(value) { prefs.edit().putBoolean("device_location", value).apply() }
    var fahrenheit: Boolean
        get() = prefs.getBoolean("fahrenheit", false)
        set(value) { prefs.edit().putBoolean("fahrenheit", value).apply() }
    var refreshMinutes: Int
        get() = prefs.getInt("refresh_minutes", 30).coerceIn(15, 360)
        set(value) { prefs.edit().putInt("refresh_minutes", value.coerceIn(15, 360)).apply() }
    var apiKey: String
        get() = runCatching { secure.getString("openweathermap_key", "").orEmpty() }.getOrDefault("")
        set(value) {
            val clean = value.trim()
            if (clean.isEmpty()) secure.edit().remove("openweathermap_key").commit()
            else check(secure.edit().putString("openweathermap_key", clean).commit()) { "Could not save the weather API key securely." }
        }
    var lastSuccessfulUpdate: Long
        get() = prefs.getLong("last_success", 0L)
        set(value) { prefs.edit().putLong("last_success", value).apply() }
}
