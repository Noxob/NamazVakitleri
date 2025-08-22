package com.noxob.namazvakti.complication

import android.Manifest
import android.content.ComponentName
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.TimeRange
import com.noxob.namazvakti.R
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZoneId
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import com.batoulapps.adhan2.CalculationMethod
import com.batoulapps.adhan2.Coordinates
import com.batoulapps.adhan2.PrayerTimes
import com.batoulapps.adhan2.Madhab
import com.batoulapps.adhan2.data.DateComponents
import kotlinx.datetime.toJavaInstant
import kotlin.math.*

class MainComplicationService : SuspendingComplicationDataSourceService() {

    private val dataClient by lazy { Wearable.getDataClient(this) }
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("prayer_cache", MODE_PRIVATE)
    }

    companion object {
        private const val TAG = "MainComplication"
        private const val LAST_LAT = "last_lat"
        private const val LAST_LNG = "last_lng"
        private const val CACHE_DAY = "cache_day"
        private const val CACHE_LAT = "cache_lat"
        private const val CACHE_LNG = "cache_lng"
        private const val CACHE_TODAY = "cache_today"
        private const val CACHE_TOMORROW = "cache_tomorrow"
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        val now = LocalDateTime.now().plusMinutes(5)
        return createComplicationData(now, "Time to next prayer")
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        return try {
            Log.d(TAG, "Complication requested: $request")
            val (lat, lng) = getLocation()
            Log.d(TAG, "Using location $lat,$lng")
            val (today, tomorrow) = fetchPrayerTimes(lat, lng)
            Log.d(TAG, "Fetched prayer times")
            val now = LocalDateTime.now()
            val (name, time) = nextPrayer(now, today, tomorrow)
            Log.d(TAG, "Next prayer $name at $time")
            val data = createComplicationData(time, "Time until $name")
            val nextMinute = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1)
            val targetMillis = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            scheduleComplicationUpdate(minOf(nextMinute, targetMillis))
            data
        } catch (e: CancellationException) {
            Log.w(TAG, "Complication request cancelled", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error creating complication", e)
            createComplicationData(LocalDateTime.now(), "Prayer time unavailable")
        }
    }

    private fun createComplicationData(targetTime: LocalDateTime, contentDescription: String): ComplicationData {
        val image = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_prayer_time)
        ).build()

        val targetInstant = targetTime.atZone(ZoneId.systemDefault()).toInstant()
        val text = TimeDifferenceComplicationText.Builder(
            TimeDifferenceStyle.SHORT_DUAL_UNIT,
            CountDownTimeReference(targetInstant)
        ).setMinimumTimeUnit(TimeUnit.MINUTES).build()

        val nowInstant = LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant()
        val range = TimeRange.between(nowInstant, targetInstant)

        return ShortTextComplicationData.Builder(
            text,
            PlainComplicationText.Builder(contentDescription).build()
        ).setMonochromaticImage(image)
            .setValidTimeRange(range)
            .build()
    }

    private fun nextPrayer(
        now: LocalDateTime,
        today: List<LocalTime>,
        tomorrow: List<LocalTime>
    ): Pair<String, LocalDateTime> {
        val names = listOf("Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha")
        for (i in today.indices) {
            val time = LocalDateTime.of(now.toLocalDate(), today[i])
            if (time.isAfter(now)) {
                return names[i] to time
            }
        }
        val nextDay = LocalDateTime.of(now.toLocalDate().plusDays(1), tomorrow[0])
        return names[0] to nextDay
    }

    private suspend fun fetchPrayerTimes(
        lat: Double,
        lng: Double
    ): Pair<List<LocalTime>, List<LocalTime>> = withContext(Dispatchers.Default) {
        val today = LocalDate.now()

        val cachedDay = prefs.getString(CACHE_DAY, null)?.let { LocalDate.parse(it) }
        val cachedLat = prefs.getFloat(CACHE_LAT, 0f).toDouble()
        val cachedLng = prefs.getFloat(CACHE_LNG, 0f).toDouble()

        if (cachedDay == today && isLocationClose(lat, lng, cachedLat, cachedLng)) {
            val todayStr = prefs.getString(CACHE_TODAY, null)
            val tomorrowStr = prefs.getString(CACHE_TOMORROW, null)
            if (todayStr != null && tomorrowStr != null) {
                return@withContext parseTimes(todayStr) to parseTimes(tomorrowStr)
            }
        }

        val tomorrow = today.plusDays(1)
        val todayTimes = computeTimes(lat, lng, today)
        val tomorrowTimes = computeTimes(lat, lng, tomorrow)
        prefs.edit()
            .putString(CACHE_DAY, today.toString())
            .putFloat(CACHE_LAT, lat.toFloat())
            .putFloat(CACHE_LNG, lng.toFloat())
            .putString(CACHE_TODAY, formatTimes(todayTimes))
            .putString(CACHE_TOMORROW, formatTimes(tomorrowTimes))
            .apply()
        todayTimes to tomorrowTimes
    }

    private fun formatTimes(times: List<LocalTime>) =
        times.joinToString(",") { it.toString() }

    private fun parseTimes(str: String): List<LocalTime> =
        str.split(',').map { LocalTime.parse(it) }

    private fun isLocationClose(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Boolean {
        val R = 6371000.0
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2).pow(2.0) + cos(phi1) * cos(phi2) * sin(dLambda / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        val distance = R * c
        return distance < 20_000 // within ~20km ≈ 1 minute difference
    }

    private fun computeTimes(lat: Double, lng: Double, date: LocalDate): List<LocalTime> {
        val coordinates = Coordinates(lat, lng)
        val params = CalculationMethod.TURKEY.parameters.copy(madhab = Madhab.HANAFI)
        val components = DateComponents(date.year, date.monthValue, date.dayOfMonth)
        val times = PrayerTimes(coordinates, components, params)
        val offsetMinutes = TimeZone.getDefault().rawOffset / 60000
        val zoneOffset = ZoneOffset.ofTotalSeconds(offsetMinutes * 60)
        return listOf(
            times.fajr,
            times.sunrise,
            times.dhuhr,
            times.asr,
            times.maghrib,
            times.isha
        ).map { instant ->
            instant.toJavaInstant().atOffset(zoneOffset).toLocalTime()
        }
    }

    private fun scheduleComplicationUpdate(triggerAtMillis: Long) {
        val delay = max(0L, triggerAtMillis - System.currentTimeMillis())
        val requester = ComplicationDataSourceUpdateRequester.create(
            this,
            ComponentName(this, MainComplicationService::class.java)
        )
        Handler(Looper.getMainLooper()).postDelayed({
            requester.requestUpdateAll()
        }, delay)
    }

    private suspend fun getLocation(): Pair<Double, Double> = withContext(Dispatchers.IO) {
        val watchLocation = if (
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            try {
                withTimeoutOrNull(2000) { fusedClient.lastLocation.await() }
            } catch (e: Exception) {
                Log.w(TAG, "Watch location unavailable", e)
                null
            }
        } else null

        val locFromWatch = watchLocation?.let { it.latitude to it.longitude }

        val locFromPhone = locFromWatch ?: run {
            val uri = Uri.parse("wear://*/location")
            val buffer = try {
                withTimeoutOrNull(2000) { dataClient.getDataItems(uri).await() }
            } catch (e: CancellationException) {
                Log.w(TAG, "Location task cancelled", e)
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error reading location", e)
                null
            }

            buffer?.use { buf ->
                if (buf.count > 0) {
                    val map = DataMapItem.fromDataItem(buf[0]).dataMap
                    map.getDouble("lat") to map.getDouble("lng")
                } else null
            }
        }

        val finalLoc = locFromPhone ?: loadLastLocation() ?: run {
            Log.w(TAG, "No location found; using fallback")
            39.91987 to 32.85427
        }

        saveLastLocation(finalLoc.first, finalLoc.second)
        finalLoc
    }

    private fun saveLastLocation(lat: Double, lng: Double) {
        prefs.edit().putFloat(LAST_LAT, lat.toFloat()).putFloat(LAST_LNG, lng.toFloat()).apply()
    }

    private fun loadLastLocation(): Pair<Double, Double>? {
        if (!prefs.contains(LAST_LAT) || !prefs.contains(LAST_LNG)) return null
        return prefs.getFloat(LAST_LAT, 0f).toDouble() to prefs.getFloat(LAST_LNG, 0f).toDouble()
    }
}
