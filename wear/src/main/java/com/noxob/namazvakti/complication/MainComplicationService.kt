package com.noxob.namazvakti.complication

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.noxob.namazvakti.data.PrayerTimesRepository
import java.time.Duration
import java.time.LocalTime

class MainComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) {
            return null
        }
        return createComplicationData("60m", "Next prayer in 60 minutes")
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val times = PrayerTimesRepository(this).getPrayerTimes()
        val now = LocalTime.now()
        val next = times?.firstOrNull { it.isAfter(now) }
        val duration = next?.let { Duration.between(now, it) }
        val text = duration?.let { formatDuration(it) } ?: "--"
        val desc = duration?.let { "Next prayer in ${formatDuration(it)}" } ?: "No data"
        return createComplicationData(text, desc)
    }

    private fun formatDuration(duration: Duration): String {
        val hours = duration.toHours()
        val minutes = duration.toMinutes() % 60
        return if (hours > 0) "${'$'}hours h ${'$'}minutes m" else "${'$'}minutes m"
    }

    private fun createComplicationData(text: String, contentDescription: String) =
        ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder(contentDescription).build()
        ).build()
}