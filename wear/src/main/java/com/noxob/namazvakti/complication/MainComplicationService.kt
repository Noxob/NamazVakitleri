package com.noxob.namazvakti.complication

import android.Manifest
import android.content.ComponentName
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
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
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.Duration
import java.time.Instant
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
        val now = LocalDateTime.now()
        val start = now.minusMinutes(30)
        val end = now.plusMinutes(30)
        val range = TimeRange.between(Instant.MIN, Instant.MAX)
        return when (type) {
            ComplicationType.SHORT_TEXT ->
                createComplicationData(type, "Dhuhr", start, end, range)
            ComplicationType.RANGED_VALUE ->
                createComplicationData(type, "Dhuhr", start, end, range)
            ComplicationType.MONOCHROMATIC_IMAGE ->
                createComplicationData(type, "Dhuhr", start, end, range)
            ComplicationType.LONG_TEXT ->
                createComplicationData(type, "Dhuhr", start, end, range)
            ComplicationType.SMALL_IMAGE ->
                createComplicationData(type, "Dhuhr", start, end, range)
            else -> null
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        return try {
            Log.d(TAG, "Complication requested: $request")
            val (lat, lng) = getLocation()
            Log.d(TAG, "Using location $lat,$lng")
            val (yesterday, today, tomorrow) = fetchPrayerTimes(lat, lng)
            Log.d(TAG, "Fetched prayer times")
            val now = LocalDateTime.now()
            val (name, start, end) = prayerWindow(now, yesterday, today, tomorrow)
            Log.d(TAG, "Next prayer $name at $end")

            val kerahat = kerahatInterval(now, today)
            val isKerahat = kerahat != null
            val (kStart, kEnd) = kerahat ?: (start to end)

            val data = createComplicationData(
                request.complicationType,
                name,
                kStart,
                kEnd,
                isKerahat
            )
            val nextMinute = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1)
            val targetMillis = kEnd.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            scheduleComplicationUpdate(minOf(nextMinute, targetMillis))
            data
        } catch (e: CancellationException) {
            Log.w(TAG, "Complication request cancelled", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error creating complication", e)
            val now = LocalDateTime.now()
            createComplicationData(
                request.complicationType,
                "Prayer",
                now,
                now.plusMinutes(1)
            )
        }
    }

    private fun createComplicationData(
        type: ComplicationType,
        prayerName: String,
        start: LocalDateTime,
        end: LocalDateTime,
        isKerahat: Boolean = false,
        range: TimeRange = TimeRange.between(
            LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant(),
            end.atZone(ZoneId.systemDefault()).toInstant()
        )
    ): ComplicationData {
        val icon = iconForPrayer(prayerName)
        val monoBuilder = MonochromaticImage.Builder(icon)
        if (isKerahat) monoBuilder.setTintColor(Color.RED)
        val mono = monoBuilder.build()
        val endInstant = end.atZone(ZoneId.systemDefault()).toInstant()
        val nowInstant = LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant()
        val duration = Duration.between(nowInstant, endInstant)
        val totalMinutes = duration.toMinutes().coerceAtLeast(0)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        val textStr = if (hours > 0) "${hours}s ${minutes}d" else "${minutes}d"
        val text = PlainComplicationText.Builder(textStr).build()
        val descStr = if (isKerahat) {
            "Kerahat until $prayerName"
        } else {
            "Time until $prayerName"
        }
        val description = PlainComplicationText.Builder(descStr).build()

        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(text, description)
                    .setMonochromaticImage(mono)
                    .setValidTimeRange(range)
                    .build()
            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(text, description)
                    .setMonochromaticImage(mono)
                    .setValidTimeRange(range)
                    .build()
            ComplicationType.MONOCHROMATIC_IMAGE ->
                MonochromaticImageComplicationData.Builder(mono, description)
                    .setValidTimeRange(range)
                    .build()
            ComplicationType.SMALL_IMAGE -> {
                val smallBuilder = SmallImage.Builder(icon, SmallImageType.ICON)
                if (isKerahat) smallBuilder.setTintColor(Color.RED)
                val small = smallBuilder.build()
                SmallImageComplicationData.Builder(small, description)
                    .setValidTimeRange(range)
                    .build()
            }
            ComplicationType.RANGED_VALUE -> {
                val startInstant = start.atZone(ZoneId.systemDefault()).toInstant()
                val total = (endInstant.toEpochMilli() - startInstant.toEpochMilli()).toFloat()
                val current = (nowInstant.toEpochMilli() - startInstant.toEpochMilli())
                    .coerceIn(0, total.toLong())
                    .toFloat()
                RangedValueComplicationData.Builder(current, 0f, total, description)
                    .setText(text)
                    .setMonochromaticImage(mono)
                    .setValidTimeRange(range)
                    .build()
            }
            else ->
                ShortTextComplicationData.Builder(text, description)
                    .setMonochromaticImage(mono)
                    .setValidTimeRange(range)
                    .build()
        }
    }

    private fun iconForPrayer(name: String): Icon {
        val res = when (name) {
            "Fajr" -> R.drawable.ic_fajr
            "Sunrise" -> R.drawable.ic_sunrise
            "Dhuhr" -> R.drawable.ic_dhuhr
            "Asr" -> R.drawable.ic_asr
            "Maghrib" -> R.drawable.ic_maghrib
            else -> R.drawable.ic_isha
        }
        return Icon.createWithResource(this, res)
    }

    private fun prayerWindow(
        now: LocalDateTime,
        yesterday: List<LocalTime>,
        today: List<LocalTime>,
        tomorrow: List<LocalTime>
    ): Triple<String, LocalDateTime, LocalDateTime> {
        val names = listOf("Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha")
        for (i in today.indices) {
            val end = LocalDateTime.of(now.toLocalDate(), today[i])
            if (end.isAfter(now)) {
                val start = if (i == 0) {
                    LocalDateTime.of(now.toLocalDate().minusDays(1), yesterday.last())
                } else {
                    LocalDateTime.of(now.toLocalDate(), today[i - 1])
                }
                return Triple(names[i], start, end)
            }
        }
        val start = LocalDateTime.of(now.toLocalDate(), today.last())
        val end = LocalDateTime.of(now.toLocalDate().plusDays(1), tomorrow[0])
        return Triple(names[0], start, end)
    }

    private fun kerahatInterval(
        now: LocalDateTime,
        today: List<LocalTime>
    ): Pair<LocalDateTime, LocalDateTime>? {
        val sunrise = LocalDateTime.of(now.toLocalDate(), today[1])
        val dhuhr = LocalDateTime.of(now.toLocalDate(), today[2])
        val maghrib = LocalDateTime.of(now.toLocalDate(), today[4])

        val sunriseEnd = sunrise.plusMinutes(45)
        val dhuhrStart = dhuhr.minusMinutes(45)
        val maghribStart = maghrib.minusMinutes(45)

        return when {
            !now.isBefore(sunrise) && now.isBefore(sunriseEnd) -> sunrise to sunriseEnd
            !now.isBefore(dhuhrStart) && now.isBefore(dhuhr) -> dhuhrStart to dhuhr
            !now.isBefore(maghribStart) && now.isBefore(maghrib) -> maghribStart to maghrib
            else -> null
        }
    }

    private suspend fun fetchPrayerTimes(
        lat: Double,
        lng: Double
    ): Triple<List<LocalTime>, List<LocalTime>, List<LocalTime>> = withContext(Dispatchers.Default) {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)

        val cachedDay = prefs.getString(CACHE_DAY, null)?.let { LocalDate.parse(it) }
        val cachedLat = prefs.getFloat(CACHE_LAT, 0f).toDouble()
        val cachedLng = prefs.getFloat(CACHE_LNG, 0f).toDouble()

        if (cachedDay == today && isLocationClose(lat, lng, cachedLat, cachedLng)) {
            val todayStr = prefs.getString(CACHE_TODAY, null)
            val tomorrowStr = prefs.getString(CACHE_TOMORROW, null)
            if (todayStr != null && tomorrowStr != null) {
                val todayTimes = parseTimes(todayStr)
                val tomorrowTimes = parseTimes(tomorrowStr)
                val yesterdayTimes = computeTimes(lat, lng, yesterday)
                return@withContext Triple(yesterdayTimes, todayTimes, tomorrowTimes)
            }
        }

        val tomorrow = today.plusDays(1)
        val yesterdayTimes = computeTimes(lat, lng, yesterday)
        val todayTimes = computeTimes(lat, lng, today)
        val tomorrowTimes = computeTimes(lat, lng, tomorrow)
        prefs.edit()
            .putString(CACHE_DAY, today.toString())
            .putFloat(CACHE_LAT, lat.toFloat())
            .putFloat(CACHE_LNG, lng.toFloat())
            .putString(CACHE_TODAY, formatTimes(todayTimes))
            .putString(CACHE_TOMORROW, formatTimes(tomorrowTimes))
            .apply()
        Triple(yesterdayTimes, todayTimes, tomorrowTimes)
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
