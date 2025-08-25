package com.noxob.namazvakti

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.TimeZone
import com.batoulapps.adhan2.CalculationMethod
import com.batoulapps.adhan2.Coordinates
import com.batoulapps.adhan2.Madhab
import com.batoulapps.adhan2.PrayerTimes
import com.batoulapps.adhan2.data.DateComponents
import kotlinx.datetime.toJavaInstant
import kotlin.math.*

object PrayerTimeCalculator {
    private const val TAG = "PrayerCalc"
    private const val PREFS = "prayer_cache"
    private const val LAST_LAT = "last_lat"
    private const val LAST_LNG = "last_lng"
    private const val LAST_CITY = "last_city"
    private const val CACHE_DAY = "cache_day"
    private const val CACHE_LAT = "cache_lat"
    private const val CACHE_LNG = "cache_lng"
    private const val CACHE_TODAY = "cache_today"
    private const val CACHE_TOMORROW = "cache_tomorrow"
    private const val LAST_METHOD = "last_method"
    private const val LAST_MADHAB = "last_madhab"
    private const val LAST_LANG = "last_lang"
    private const val LAST_SYNC = "last_settings_sync"
    private const val SYNC_INTERVAL_MS = 30_000L

    suspend fun getLocation(context: Context): Pair<Double, Double> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dataClient = Wearable.getDataClient(context)
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)

        val watchLocation = if (
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
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

        val finalLoc = locFromPhone ?: loadLastLocation(prefs) ?: run {
            Log.w(TAG, "No location found; using fallback")
            39.91987 to 32.85427
        }

        saveLastLocation(prefs, finalLoc.first, finalLoc.second)
        finalLoc
    }

    suspend fun getCityName(context: Context, lat: Double, lng: Double): String =
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val geocoder = android.location.Geocoder(context)
            val dataClient = Wearable.getDataClient(context)

            val city = try {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()?.let { addr ->
                    listOfNotNull(addr.locality, addr.subAdminArea, addr.adminArea).firstOrNull()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Geocoder failed", e)
                null
            } ?: run {
                val uri = Uri.parse("wear://*/city")
                val buffer = try {
                    withTimeoutOrNull(2000) { dataClient.getDataItems(uri).await() }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading city", e)
                    null
                }
                buffer?.use { buf ->
                    if (buf.count > 0) {
                        DataMapItem.fromDataItem(buf[0]).dataMap.getString("name")
                    } else null
                }
            } ?: prefs.getString(LAST_CITY, "") ?: ""

            if (city.isNotEmpty()) {
                prefs.edit().putString(LAST_CITY, city).apply()
            }

            city
        }

    private suspend fun syncSettings(context: Context, prefs: SharedPreferences) {
        val now = System.currentTimeMillis()
        val last = prefs.getLong(LAST_SYNC, 0L)
        if (now - last > SYNC_INTERVAL_MS) {
            try {
                val nodeClient: NodeClient = Wearable.getNodeClient(context)
                val messageClient: MessageClient = Wearable.getMessageClient(context)
                val nodes = nodeClient.connectedNodes.await()
                for (node in nodes) {
                    try {
                        messageClient.sendMessage(node.id, "/request_settings", ByteArray(0)).await()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed requesting settings", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting settings", e)
            }
        }

        val dataClient = Wearable.getDataClient(context)
        val uri = Uri.parse("wear://*/settings")
        val buffer = try {
            withTimeoutOrNull(2000) { dataClient.getDataItems(uri).await() }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading settings", e)
            null
        }
        buffer?.use { buf ->
            if (buf.count > 0) {
                val map = DataMapItem.fromDataItem(buf[0]).dataMap
                prefs.edit()
                    .putString(LAST_METHOD, map.getString("calc_method", CalculationMethod.TURKEY.name))
                    .putString(LAST_MADHAB, map.getString("madhab", Madhab.SHAFI.name))
                    .putString(LAST_LANG, map.getString("language", "tr"))
                    .putLong(LAST_SYNC, now)
                    .apply()
            }
        }
    }

    suspend fun fetchPrayerTimes(
        context: Context,
        lat: Double,
        lng: Double
    ): Triple<List<LocalTime>, List<LocalTime>, List<LocalTime>> = withContext(Dispatchers.Default) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        syncSettings(context, prefs)
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
                val yesterdayTimes = computeTimes(lat, lng, yesterday, prefs)
                return@withContext Triple(yesterdayTimes, todayTimes, tomorrowTimes)
            }
        }

        val tomorrow = today.plusDays(1)
        val yesterdayTimes = computeTimes(lat, lng, yesterday, prefs)
        val todayTimes = computeTimes(lat, lng, today, prefs)
        val tomorrowTimes = computeTimes(lat, lng, tomorrow, prefs)
        prefs.edit()
            .putString(CACHE_DAY, today.toString())
            .putFloat(CACHE_LAT, lat.toFloat())
            .putFloat(CACHE_LNG, lng.toFloat())
            .putString(CACHE_TODAY, formatTimes(todayTimes))
            .putString(CACHE_TOMORROW, formatTimes(tomorrowTimes))
            .apply()
        Triple(yesterdayTimes, todayTimes, tomorrowTimes)
    }

    fun prayerWindow(
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

    fun kerahatInterval(
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

    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(LAST_LANG, "tr") ?: "tr"
    }

    fun translatePrayerName(name: String, lang: String): String = when (lang) {
        "tr" -> when (name) {
            "Fajr" -> "İmsak"
            "Sunrise" -> "Güneş"
            "Dhuhr" -> "Öğle"
            "Asr" -> "İkindi"
            "Maghrib" -> "Akşam"
            "Isha" -> "Yatsı"
            else -> name
        }
        else -> name
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
        return distance < 20_000
    }

    private fun computeTimes(
        lat: Double,
        lng: Double,
        date: LocalDate,
        prefs: SharedPreferences
    ): List<LocalTime> {
        val coordinates = Coordinates(lat, lng)
        val methodName = prefs.getString(LAST_METHOD, CalculationMethod.TURKEY.name)!!
        val madhabName = prefs.getString(LAST_MADHAB, Madhab.SHAFI.name)!!
        val params = CalculationMethod.valueOf(methodName).parameters.copy(
            madhab = Madhab.valueOf(madhabName)
        )
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

    private fun saveLastLocation(prefs: SharedPreferences, lat: Double, lng: Double) {
        prefs.edit().putFloat(LAST_LAT, lat.toFloat()).putFloat(LAST_LNG, lng.toFloat()).apply()
    }

    private fun loadLastLocation(prefs: SharedPreferences): Pair<Double, Double>? {
        if (!prefs.contains(LAST_LAT) || !prefs.contains(LAST_LNG)) return null
        return prefs.getFloat(LAST_LAT, 0f).toDouble() to prefs.getFloat(LAST_LNG, 0f).toDouble()
    }
}

