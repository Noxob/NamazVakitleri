package com.noxob.namazvakti

import com.batoulapps.adhan2.CalculationMethod
import com.batoulapps.adhan2.Coordinates
import com.batoulapps.adhan2.Madhab
import com.batoulapps.adhan2.PrayerTimes
import com.batoulapps.adhan2.data.DateComponents
import kotlinx.datetime.toJavaInstant
import java.time.*
import java.time.temporal.ChronoUnit

object PrayerUtils {
    private val names = listOf("Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha")

    fun computePrayerTimes(lat: Double, lng: Double, date: LocalDate): List<LocalTime> {
        val coordinates = Coordinates(lat, lng)
        val params = CalculationMethod.TURKEY.parameters.copy(madhab = Madhab.HANAFI)
        val components = DateComponents(date.year, date.monthValue, date.dayOfMonth)
        val times = PrayerTimes(coordinates, components, params)
        val zoneOffset = ZoneId.systemDefault().rules.getOffset(Instant.now())
        return listOf(times.fajr, times.sunrise, times.dhuhr, times.asr, times.maghrib, times.isha)
            .map { it.toJavaInstant().atOffset(zoneOffset).toLocalTime() }
    }

    fun nextPrayer(
        now: LocalDateTime,
        today: List<LocalTime>,
        tomorrow: List<LocalTime>
    ): Pair<String, LocalDateTime> {
        for (i in today.indices) {
            val time = LocalDateTime.of(now.toLocalDate(), today[i])
            if (time.isAfter(now)) {
                return names[i] to time
            }
        }
        return names[0] to LocalDateTime.of(now.toLocalDate().plusDays(1), tomorrow[0])
    }

    fun kerahatInterval(now: LocalDateTime, today: List<LocalTime>): Pair<LocalDateTime, LocalDateTime>? {
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

    fun formatTime(time: LocalTime): String = time.truncatedTo(ChronoUnit.MINUTES).toString()

    fun formatDuration(duration: Duration): String {
        val totalMinutes = duration.toMinutes().coerceAtLeast(0)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "%dh %02dm".format(hours, minutes) else "%02dm".format(minutes)
    }

    fun names(): List<String> = names
}

