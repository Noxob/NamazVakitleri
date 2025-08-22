package com.noxob.namazvakti

import android.content.Context
import android.location.Geocoder
import com.batoulapps.adhan2.CalculationMethod
import com.batoulapps.adhan2.Coordinates
import com.batoulapps.adhan2.Madhab
import com.batoulapps.adhan2.PrayerTimes
import com.batoulapps.adhan2.data.DateComponents
import kotlinx.datetime.toJavaInstant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone

fun computeTimes(lat: Double, lng: Double, date: LocalDate): List<LocalTime> {
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
    ).map { it.toJavaInstant().atOffset(zoneOffset).toLocalTime() }
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

fun formatTime(time: LocalTime): String = time.toString().substring(0,5)

fun resolveCity(context: Context, lat: Double, lng: Double): String = try {
    val geocoder = Geocoder(context, Locale.getDefault())
    geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()?.locality ?: "Unknown"
} catch (e: Exception) {
    "Unknown"
}
