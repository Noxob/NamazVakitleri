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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.google.android.gms.tasks.Tasks
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import com.batoulapps.adhan2.CalculationMethod
import com.batoulapps.adhan2.Coordinates
import com.batoulapps.adhan2.PrayerTimes
import com.batoulapps.adhan2.Madhab
import com.batoulapps.adhan2.data.DateComponents
import kotlinx.datetime.toJavaInstant

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
    ): Pair<List<LocalTime>, List<LocalTime>> = withContext(Dispatchers.Default) {
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        computeTimes(lat, lng, today) to computeTimes(lat, lng, tomorrow)
    }

    private fun computeTimes(lat: Double, lng: Double, date: LocalDate): List<LocalTime> {
        val coordinates = Coordinates(lat, lng)
        val params = CalculationMethod.TURKEY.parameters.copy(madhab = Madhab.HANAFI)
        val components = DateComponents(date.year, date.monthValue, date.dayOfMonth)
        val times = PrayerTimes(coordinates, components, params)
        val zone = ZoneId.systemDefault()
        return listOf(
            times.fajr,
            times.sunrise,
            times.dhuhr,
            times.asr,
            times.maghrib,
            times.isha
        ).map { it.toJavaInstant().atZone(zone).toLocalTime() }
    }

    private suspend fun getLocation(): Pair<Double, Double> = withContext(Dispatchers.IO) {
        val uri = Uri.parse("wear://*/location")
        val buffer = try {
            withTimeoutOrNull(2000) {
                Tasks.await(dataClient.getDataItems(uri))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error reading location", e)
            null
        }

        val location = buffer?.use { buf ->
            if (buf.count > 0) {
                val map = DataMapItem.fromDataItem(buf[0]).dataMap
                map.getDouble("lat") to map.getDouble("lng")
            } else null
        }

        location ?: run {
            Log.w(TAG, "No location found; using fallback")
            39.91987 to 32.85427
        }
    }
}
