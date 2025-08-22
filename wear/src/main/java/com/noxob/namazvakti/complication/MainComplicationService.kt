package com.noxob.namazvakti.complication

import android.net.Uri
import android.graphics.drawable.Icon
import android.util.Log
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import com.noxob.namazvakti.R
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.TimeZone

class MainComplicationService : SuspendingComplicationDataSourceService() {

    private val dataClient by lazy { Wearable.getDataClient(this) }

    companion object {
        private const val TAG = "MainComplication"
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        return createComplicationData("0m", "Time to next prayer")
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        return try {
            Log.d(TAG, "Complication requested: $request")
            val (lat, lng) = getLocation()
            Log.d(TAG, "Using location $lat,$lng")
            val (today, tomorrow) = fetchPrayerTimes(lat, lng)
            Log.d(TAG, "Fetched prayer times")
            val now = LocalDateTime.now()
            val (name, duration) = nextPrayer(now, today, tomorrow)
            val text = formatDuration(duration)
            Log.d(TAG, "Next prayer $name in $text")
            createComplicationData(text, "Time until $name")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating complication", e)
            createComplicationData("--", "Prayer time unavailable")
        }
    }

    private fun createComplicationData(text: String, contentDescription: String): ComplicationData {
        val image = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_prayer_time)
        ).build()

        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder(contentDescription).build()
        ).setMonochromaticImage(image).build()
    }

    private fun formatDuration(duration: Duration): String {
        val hours = duration.toHours()
        val minutes = duration.minusHours(hours).toMinutes()
        return if (hours > 0) {
            "${hours}h ${minutes}m"
        } else {
            "${minutes}m"
        }
    }

    private fun nextPrayer(
        now: LocalDateTime,
        today: List<LocalTime>,
        tomorrow: List<LocalTime>
    ): Pair<String, Duration> {
        val names = listOf("Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha")
        for (i in today.indices) {
            val time = LocalDateTime.of(now.toLocalDate(), today[i])
            if (time.isAfter(now)) {
                return names[i] to Duration.between(now, time)
            }
        }
        val nextDay = LocalDateTime.of(now.toLocalDate().plusDays(1), tomorrow[0])
        return names[0] to Duration.between(now, nextDay)
    }

    private suspend fun fetchPrayerTimes(
        lat: Double,
        lng: Double
    ): Pair<List<LocalTime>, List<LocalTime>> = withContext(Dispatchers.IO) {
        val date = LocalDate.now()
        val offset = TimeZone.getDefault().rawOffset / 60000
        val url = URL(
            "https://vakit.vercel.app/api/timesForGPS?lat=$lat&lng=$lng&date=$date&days=2&timezoneOffset=$offset&calculationMethod=Turkey&lang=en"
        )
        Log.d(TAG, "Requesting $url")
        val connection = url.openConnection() as HttpURLConnection
        try {
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val json = JSONObject(reader.readText())
            reader.close()
            val times = json.getJSONObject("times")
            val todayArray = times.getJSONArray(date.toString())
            val tomorrowArray = times.getJSONArray(date.plusDays(1).toString())
            parseTimes(todayArray) to parseTimes(tomorrowArray)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseTimes(array: JSONArray): List<LocalTime> {
        val list = mutableListOf<LocalTime>()
        for (i in 0 until array.length()) {
            list.add(LocalTime.parse(array.getString(i)))
        }
        return list
    }

    private suspend fun getLocation(): Pair<Double, Double> = withContext(Dispatchers.IO) {
        val uri = Uri.parse("wear://*/location")
        try {
            val dataItem = dataClient.getDataItem(uri).await()
            if (dataItem != null) {
                val map = DataMapItem.fromDataItem(dataItem).dataMap
                map.getDouble("lat") to map.getDouble("lng")
            } else {
                Log.w(TAG, "No location found; using fallback")
                39.91987 to 32.85427
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading location", e)
            39.91987 to 32.85427
        }
    }
}
