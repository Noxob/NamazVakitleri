package com.noxob.namazvakti.complication

import android.content.ComponentName
import android.graphics.drawable.Icon
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.max
import com.noxob.namazvakti.PrayerTimeCalculator
import com.noxob.namazvakti.tile.MainTileService

class MainComplicationService : SuspendingComplicationDataSourceService() {

    companion object {
        private const val TAG = "MainComplication"
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val now = LocalDateTime.now()
        val start = now.minusMinutes(30)
        val end = now.plusMinutes(30)
        val range = TimeRange.between(Instant.MIN, Instant.MAX)
        val lang = PrayerTimeCalculator.getLanguage(this)
        return when (type) {
            ComplicationType.SHORT_TEXT ->
                createComplicationData(type, "Dhuhr", start, end, lang, range = range)
            ComplicationType.RANGED_VALUE ->
                createComplicationData(type, "Dhuhr", start, end, lang, range = range)
            ComplicationType.MONOCHROMATIC_IMAGE ->
                createComplicationData(type, "Dhuhr", start, end, lang, range = range)
            ComplicationType.LONG_TEXT ->
                createComplicationData(type, "Dhuhr", start, end, lang, range = range)
            ComplicationType.SMALL_IMAGE ->
                createComplicationData(type, "Dhuhr", start, end, lang, range = range)
            else -> null
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        MainTileService.refreshIfStale(this)
        return try {
            Log.d(TAG, "Complication requested: $request")
            val (lat, lng) = PrayerTimeCalculator.getLocation(this)
            Log.d(TAG, "Using location $lat,$lng")
            val (yesterday, today, tomorrow) = PrayerTimeCalculator.fetchPrayerTimes(this, lat, lng)
            Log.d(TAG, "Fetched prayer times")
            val now = LocalDateTime.now()
            val (name, start, end) = PrayerTimeCalculator.prayerWindow(now, yesterday, today, tomorrow)
            Log.d(TAG, "Next prayer $name at $end")

            val kerahat = PrayerTimeCalculator.kerahatInterval(now, today)
            val isKerahat = kerahat != null
            val (kStart, kEnd) = kerahat ?: (start to end)
            val lang = PrayerTimeCalculator.getLanguage(this)

            val data = createComplicationData(
                request.complicationType,
                name,
                kStart,
                kEnd,
                lang,
                isKerahat
            )
            val nextMinute = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(1)
            val targetMillis = kEnd.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            scheduleComplicationUpdate(minOf(nextMinute, targetMillis))
            data
        } catch (e: CancellationException) {
            Log.w(TAG, "Complication request cancelled", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error creating complication", e)
            val now = LocalDateTime.now()
            scheduleComplicationUpdate(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1))
            createComplicationData(
                request.complicationType,
                "Prayer",
                now,
                now.plusMinutes(1),
                PrayerTimeCalculator.getLanguage(this)
            )
        }
    }

    private fun createComplicationData(
        type: ComplicationType,
        rawPrayerName: String,
        start: LocalDateTime,
        end: LocalDateTime,
        lang: String,
        isKerahat: Boolean = false,
        range: TimeRange = TimeRange.between(
            LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant(),
            end.atZone(ZoneId.systemDefault()).toInstant()
        )
    ): ComplicationData {
        val displayName = PrayerTimeCalculator.translatePrayerName(rawPrayerName, lang)
        val icon = iconForPrayer(rawPrayerName)
        if (isKerahat) icon.setTint(Color.RED)
        val mono = MonochromaticImage.Builder(icon).build()
        val endInstant = end.atZone(ZoneId.systemDefault()).toInstant()
        val nowInstant = LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant()
        val duration = Duration.between(nowInstant, endInstant)
        val totalMinutes = duration.toMinutes().coerceAtLeast(0)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        val textStr = if (hours > 0) "${hours}s ${minutes}d" else "${minutes}d"
        val text = PlainComplicationText.Builder(textStr).build()
        val descStr = if (isKerahat) {
            if (lang == "tr") "Kerahat ${displayName}'e kadar" else "Kerahat until $displayName"
        } else {
            if (lang == "tr") "$displayName'e kadar" else "Time until $displayName"
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
                val small = SmallImage.Builder(icon, SmallImageType.ICON).build()
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
    private fun scheduleComplicationUpdate(triggerAtMillis: Long) {
        val delay = max(0L, triggerAtMillis - System.currentTimeMillis())
        Handler(Looper.getMainLooper()).postDelayed({
            val requester = ComplicationDataSourceUpdateRequester.create(
                applicationContext,
                ComponentName(applicationContext, MainComplicationService::class.java)
            )
            requester.requestUpdateAll()
        }, delay)
    }
}
