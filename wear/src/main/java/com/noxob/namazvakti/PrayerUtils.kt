package com.noxob.namazvakti

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
import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object PrayerUtils {

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
        ).map { instant ->
            instant.toJavaInstant().atOffset(zoneOffset).toLocalTime()
        }
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

    fun isLocationClose(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Boolean {
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
}
