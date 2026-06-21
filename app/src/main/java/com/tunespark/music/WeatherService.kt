package com.tunespark.music

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale

data class WeatherInfo(
    val cityName: String,
    val temperature: Double,
    val description: String,
    val emoji: String
)

object WeatherService {
    private val client = OkHttpClient()

    fun fetchWeather(locationString: String): WeatherInfo? {
        val (cityName, lat, lng) = parseLocation(locationString)
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lng&current_weather=true"

        val request = Request.Builder()
            .url(url)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyString = response.body?.string() ?: return null
                val json = JSONObject(bodyString)
                if (json.has("current_weather")) {
                    val currentWeather = json.getJSONObject("current_weather")
                    val temp = currentWeather.getDouble("temperature")
                    val code = currentWeather.getInt("weathercode")
                    val (desc, emoji) = getWeatherCodeDetails(code)
                    return WeatherInfo(cityName, temp, desc, emoji)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun parseLocation(locationString: String): Triple<String, Double, Double> {
        val defaultLat = 37.7749
        val defaultLng = -122.4194
        val defaultCity = "San Francisco, CA"

        try {
            val startIndex = locationString.indexOf('(')
            val endIndex = locationString.indexOf(')')
            if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
                val cityName = locationString.substring(0, startIndex).trim()
                val coordsStr = locationString.substring(startIndex + 1, endIndex)
                val parts = coordsStr.split(",")
                if (parts.size == 2) {
                    val lat = parts[0].trim().toDoubleOrNull()
                    val lng = parts[1].trim().toDoubleOrNull()
                    if (lat != null && lng != null) {
                        return Triple(cityName.ifEmpty { "Current Location" }, lat, lng)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Triple(defaultCity, defaultLat, defaultLng)
    }

    private fun getWeatherCodeDetails(code: Int): Pair<String, String> {
        return when (code) {
            0 -> Pair("Clear Sky", "☀️")
            1, 2, 3 -> Pair("Partly Cloudy", "⛅")
            45, 48 -> Pair("Foggy", "🌫️")
            51, 53, 55 -> Pair("Drizzle", "🌧️")
            56, 57 -> Pair("Freezing Drizzle", "🌧️")
            61, 63, 65 -> Pair("Rainy", "🌧️")
            66, 67 -> Pair("Freezing Rain", "🌧️")
            71, 73, 75 -> Pair("Snowy", "❄️")
            77 -> Pair("Snow Grains", "❄️")
            80, 81, 82 -> Pair("Rain Showers", "🌧️")
            85, 86 -> Pair("Snow Showers", "❄️")
            95 -> Pair("Thunderstorm", "⛈️")
            96, 99 -> Pair("Thunderstorm with Hail", "⛈️")
            else -> Pair("Unknown", "🌡️")
        }
    }
}
