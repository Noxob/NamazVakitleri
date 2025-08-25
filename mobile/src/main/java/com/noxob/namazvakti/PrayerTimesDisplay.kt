package com.noxob.namazvakti

import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class PrayerTimesDisplay(
    val fajr: LocalTime,
    val sunrise: LocalTime,
    val dhuhr: LocalTime,
    val asr: LocalTime,
    val maghrib: LocalTime,
    val isha: LocalTime,
) {
    fun asList(): List<Pair<String, LocalTime>> = listOf(
        "Fajr" to fajr,
        "Sunrise" to sunrise,
        "Dhuhr" to dhuhr,
        "Asr" to asr,
        "Maghrib" to maghrib,
        "Isha" to isha,
    )
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun formatTime(time: LocalTime): String = time.format(timeFormatter)

fun formatDuration(duration: Duration): String {
    val hours = duration.toHours()
    val minutes = duration.toMinutes() % 60
    return buildString {
        if (hours > 0) append("${hours}h ")
        append("${minutes}m")
    }
}

fun nextPrayer(now: LocalTime, times: PrayerTimesDisplay): Pair<String, LocalTime> =
    times.asList().firstOrNull { it.second.isAfter(now) } ?: times.asList().first()

fun isKerahat(now: LocalTime, times: PrayerTimesDisplay): Boolean {
    val morningStart = times.sunrise
    val morningEnd = times.sunrise.plusMinutes(20)
    val eveningStart = times.maghrib.minusMinutes(20)
    val eveningEnd = times.maghrib
    return (now.isAfter(morningStart) && now.isBefore(morningEnd)) ||
            (now.isAfter(eveningStart) && now.isBefore(eveningEnd))
}
