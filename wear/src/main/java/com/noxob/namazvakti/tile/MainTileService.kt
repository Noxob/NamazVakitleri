package com.noxob.namazvakti.tile

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.tiles.tooling.preview.Preview
import androidx.wear.tiles.tooling.preview.TilePreviewData
import androidx.wear.tooling.preview.devices.WearDevices
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService
import com.noxob.namazvakti.PrayerTimeCalculator
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import kotlin.math.max

private const val RESOURCES_VERSION = "0"

/**
 * Skeleton for a tile with no images.
 */
@OptIn(ExperimentalHorologistApi::class)
class MainTileService : SuspendingTileService() {

    override suspend fun resourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ) = resources(requestParams)

    override suspend fun tileRequest(
        requestParams: RequestBuilders.TileRequest
    ) = tile(requestParams, this)
}

private fun resources(
    requestParams: RequestBuilders.ResourcesRequest
): ResourceBuilders.Resources {
    return ResourceBuilders.Resources.Builder()
        .setVersion(RESOURCES_VERSION)
        .build()
}

private suspend fun tile(
    requestParams: RequestBuilders.TileRequest,
    context: Context,
): TileBuilders.Tile {
    val (lat, lng) = PrayerTimeCalculator.getLocation(context)
    val city = PrayerTimeCalculator.getCityName(context, lat, lng)
    val (yesterday, today, tomorrow) = PrayerTimeCalculator.fetchPrayerTimes(context, lat, lng)
    val now = LocalDateTime.now()
    val lang = PrayerTimeCalculator.getLanguage(context)
    val (nextName, _, nextEnd) = PrayerTimeCalculator.prayerWindow(now, yesterday, today, tomorrow)
    val displayNextName = PrayerTimeCalculator.translatePrayerName(nextName, lang)
    val countdown = Duration.between(now, nextEnd)
    val rawNames = listOf("Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha")
    val times = rawNames.zip(today).map { (n, t) ->
        PrayerTimeCalculator.translatePrayerName(n, lang) to t
    }
    val kerahat = if (PrayerTimeCalculator.kerahatInterval(now, today) != null) {
        if (lang == "tr") "Kerahat" else "Kerahat"
    } else {
        if (lang == "tr") "Normal" else "Normal"
    }

    val layout = tileLayout(
        requestParams,
        context,
        city,
        displayNextName,
        nextEnd.toLocalTime(),
        countdown,
        times,
        kerahat
    )

    val nextUpdate = minOf(
        System.currentTimeMillis() + 60_000,
        nextEnd.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    )
    scheduleTileUpdate(context, nextUpdate)

    val singleTileTimeline = TimelineBuilders.Timeline.Builder()
        .addTimelineEntry(
            TimelineBuilders.TimelineEntry.Builder()
                .setLayout(
                    LayoutElementBuilders.Layout.Builder()
                        .setRoot(layout)
                        .build()
                )
                .build()
        )
        .build()

    return TileBuilders.Tile.Builder()
        .setResourcesVersion(RESOURCES_VERSION)
        .setTileTimeline(singleTileTimeline)
        .build()
}

private fun scheduleTileUpdate(context: Context, triggerAtMillis: Long) {
    val delay = max(0L, triggerAtMillis - System.currentTimeMillis())
    val updater = TileService.getUpdater(context)
    Handler(Looper.getMainLooper()).postDelayed({
        updater.requestUpdate(MainTileService::class.java)
    }, delay)
}

private fun tileLayout(
    requestParams: RequestBuilders.TileRequest,
    context: Context,
    city: String,
    nextName: String,
    nextTime: LocalTime,
    countdown: Duration,
    times: List<Pair<String, LocalTime>>,
    kerahat: String,
): LayoutElementBuilders.LayoutElement {
    val column = LayoutElementBuilders.Column.Builder()
    if (city.isNotEmpty()) {
        column.addContent(
            Text.Builder(context, city)
                .setColor(argb(Colors.DEFAULT.onSurface))
                .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                .build()
        )
    }
    column.addContent(
            Text.Builder(context, "$nextName ${formatTime(nextTime)}")
                .setColor(argb(Colors.DEFAULT.onSurface))
                .setTypography(Typography.TYPOGRAPHY_TITLE3)
                .build()
        )
    column.addContent(
        Text.Builder(context, "${formatDuration(countdown)}")
            .setColor(argb(Colors.DEFAULT.onSurface))
            .setTypography(Typography.TYPOGRAPHY_CAPTION1)
            .build()
    )

    times.forEach { (name, time) ->
        column.addContent(
            Text.Builder(context, "$name ${formatTime(time)}")
                .setColor(argb(Colors.DEFAULT.onSurface))
                .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                .build()
        )
    }

    column.addContent(
        Text.Builder(context, kerahat)
            .setColor(argb(Colors.DEFAULT.onSurface))
            .setTypography(Typography.TYPOGRAPHY_CAPTION2)
            .build()
    )

    return PrimaryLayout.Builder(requestParams.deviceConfiguration)
        .setResponsiveContentInsetEnabled(true)
        .setContent(column.build())
        .build()
}

@Preview(device = WearDevices.SMALL_ROUND)
@Preview(device = WearDevices.LARGE_ROUND)
fun tilePreview(context: Context) = TilePreviewData(::resources) {
    runBlocking { tile(it, context) }
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun formatTime(time: LocalTime): String = time.format(timeFormatter)

private fun formatDuration(duration: Duration): String {
    val hours = duration.toHours()
    val minutes = duration.toMinutes() % 60
    return buildString {
        if (hours > 0) append("${hours}h ")
        append("${minutes}m")
    }
}