package com.noxob.namazvakti

import android.content.Context
import com.noxob.namazvakti.R
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant

data class PrayerTimes(
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

fun formatDuration(context: Context, duration: Duration): String {
    val hours = duration.toHours()
    val minutes = duration.toMinutes() % 60
    val seconds = duration.seconds % 60
    val h = context.getString(R.string.abbr_hour)
    val m = context.getString(R.string.abbr_minute)
    val s = context.getString(R.string.abbr_second)
    return buildString {
        if (hours > 0) append("${hours}$h ")
        if (minutes > 0 || hours > 0) append("${minutes}$m ")
        append("${seconds}$s")
    }
}

fun nextPrayer(now: LocalTime, times: PrayerTimes): Pair<String, LocalTime> =
    times.asList().firstOrNull { it.second.isAfter(now) } ?: times.asList().first()

fun isKerahat(now: LocalTime, times: PrayerTimes): Boolean {
    val morningStart = times.sunrise
    val morningEnd = times.sunrise.plusMinutes(20)
    val eveningStart = times.maghrib.minusMinutes(20)
    val eveningEnd = times.maghrib
    return (now.isAfter(morningStart) && now.isBefore(morningEnd)) ||
            (now.isAfter(eveningStart) && now.isBefore(eveningEnd))
}

fun Instant.toLocalTime(): LocalTime =
    this.toJavaInstant().atZone(ZoneId.systemDefault()).toLocalTime()
