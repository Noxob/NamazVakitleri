package com.noxob.namazvakti.tile

import android.content.Context
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
import androidx.wear.tiles.tooling.preview.Preview
import androidx.wear.tiles.tooling.preview.TilePreviewData
import androidx.wear.tooling.preview.devices.WearDevices
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService
import com.noxob.namazvakti.prayer.PrayerTimesRepository

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
    ): TileBuilders.Tile {
        val repo = PrayerTimesRepository(this)
        val (lat, lng) = repo.getLocation()
        val (yesterday, today, tomorrow) = repo.fetchPrayerTimes(lat, lng)
        val now = LocalDateTime.now()
        val (nextName, _, nextDateTime) = repo.prayerWindow(now, yesterday, today, tomorrow)
        val countdown = Duration.between(now, nextDateTime)
        val isKerahat = repo.kerahatInterval(now, today) != null
        val data = TileData(
            nextName = nextName,
            nextTime = nextDateTime.toLocalTime(),
            countdown = countdown,
            todayTimes = today,
            isKerahat = isKerahat
        )
        return tile(requestParams, this, data)
    }
}

private fun resources(
    requestParams: RequestBuilders.ResourcesRequest
): ResourceBuilders.Resources {
    return ResourceBuilders.Resources.Builder()
        .setVersion(RESOURCES_VERSION)
        .build()
}

private fun tile(
    requestParams: RequestBuilders.TileRequest,
    context: Context,
    data: TileData,
): TileBuilders.Tile {
    val singleTileTimeline = TimelineBuilders.Timeline.Builder()
        .addTimelineEntry(
            TimelineBuilders.TimelineEntry.Builder()
                .setLayout(
                    LayoutElementBuilders.Layout.Builder()
                        .setRoot(tileLayout(requestParams, context, data))
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

private fun tileLayout(
    requestParams: RequestBuilders.TileRequest,
    context: Context,
    data: TileData,
): LayoutElementBuilders.LayoutElement {
    val names = listOf("Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha")
    val others = names.zip(data.todayTimes)
        .joinToString(" \n") { "${it.first}: ${formatTime(it.second)}" }
    val kerahat = if (data.isKerahat) "Kerahat" else "Normal"

    val column = LayoutElementBuilders.Column.Builder()
        .addContent(
            Text.Builder(context, "İstanbul")
                .setColor(argb(Colors.DEFAULT.onSurface))
                .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                .build()
        )
        .addContent(
            Text.Builder(context, "${data.nextName} ${formatTime(data.nextTime)}")
                .setColor(argb(Colors.DEFAULT.onSurface))
                .setTypography(Typography.TYPOGRAPHY_TITLE3)
                .build()
        )
        .addContent(
            Text.Builder(context, "${formatDuration(data.countdown)}")
                .setColor(argb(Colors.DEFAULT.onSurface))
                .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                .build()
        )
        .addContent(
            Text.Builder(context, others)
                .setColor(argb(Colors.DEFAULT.onSurface))
                .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                .build()
        )
        .addContent(
            Text.Builder(context, kerahat)
                .setColor(argb(Colors.DEFAULT.onSurface))
                .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                .build()
        )
        .build()

    return PrimaryLayout.Builder(requestParams.deviceConfiguration)
        .setResponsiveContentInsetEnabled(true)
        .setContent(column)
        .build()
}

data class TileData(
    val nextName: String,
    val nextTime: LocalTime,
    val countdown: Duration,
    val todayTimes: List<LocalTime>,
    val isKerahat: Boolean,
)

@Preview(device = WearDevices.SMALL_ROUND)
@Preview(device = WearDevices.LARGE_ROUND)
fun tilePreview(context: Context) = TilePreviewData(::resources) {
    val sample = TileData(
        nextName = "Dhuhr",
        nextTime = LocalTime.of(13, 0),
        countdown = Duration.ofHours(1),
        todayTimes = listOf(
            LocalTime.of(5, 0),
            LocalTime.of(6, 30),
            LocalTime.of(13, 0),
            LocalTime.of(17, 0),
            LocalTime.of(20, 30),
            LocalTime.of(22, 0),
        ),
        isKerahat = false
    )
    tile(it, context, sample)
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