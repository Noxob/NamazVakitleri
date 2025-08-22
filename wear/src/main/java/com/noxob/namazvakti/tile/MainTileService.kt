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
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.tooling.preview.Preview
import androidx.wear.tiles.tooling.preview.TilePreviewData
import androidx.wear.tooling.preview.devices.WearDevices
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService

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

private fun tile(
    requestParams: RequestBuilders.TileRequest,
    context: Context,
): TileBuilders.Tile {
    val singleTileTimeline = TimelineBuilders.Timeline.Builder()
        .addTimelineEntry(
            TimelineBuilders.TimelineEntry.Builder()
                .setLayout(
                    LayoutElementBuilders.Layout.Builder()
                        .setRoot(tileLayout(requestParams, context))
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
): LayoutElementBuilders.LayoutElement {
    val prayerTimes = samplePrayerTimes()
    val now = LocalTime.now()
    val (nextName, nextTime) = nextPrayer(now, prayerTimes)
    val countdown = Duration.between(now, nextTime)
    val others = prayerTimes.asList().joinToString(" \n") { "${it.first}: ${formatTime(it.second)}" }
    val kerahat = if (isKerahat(now, prayerTimes)) "Kerahat" else "Normal"

    val column = LayoutElementBuilders.Column.Builder()
        .addContent(
            Text.Builder(context, "İstanbul")
                .setColor(argb(Colors.DEFAULT.onSurface))
                .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                .build()
        )
        .addContent(
            Text.Builder(context, "$nextName ${formatTime(nextTime)}")
                .setColor(argb(Colors.DEFAULT.onSurface))
                .setTypography(Typography.TYPOGRAPHY_TITLE3)
                .build()
        )
        .addContent(
            Text.Builder(context, "${formatDuration(countdown)}")
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

@Preview(device = WearDevices.SMALL_ROUND)
@Preview(device = WearDevices.LARGE_ROUND)
fun tilePreview(context: Context) = TilePreviewData(::resources) {
    tile(it, context)
}

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

private fun samplePrayerTimes() = PrayerTimes(
    fajr = LocalTime.of(5, 0),
    sunrise = LocalTime.of(6, 30),
    dhuhr = LocalTime.of(13, 0),
    asr = LocalTime.of(17, 0),
    maghrib = LocalTime.of(20, 30),
    isha = LocalTime.of(22, 0),
)

private fun nextPrayer(now: LocalTime, times: PrayerTimes): Pair<String, LocalTime> =
    times.asList().firstOrNull { it.second.isAfter(now) } ?: times.asList().first()

private fun isKerahat(now: LocalTime, times: PrayerTimes): Boolean {
    val morningStart = times.sunrise
    val morningEnd = times.sunrise.plusMinutes(20)
    val eveningStart = times.maghrib.minusMinutes(20)
    val eveningEnd = times.maghrib
    return (now.isAfter(morningStart) && now.isBefore(morningEnd)) ||
            (now.isAfter(eveningStart) && now.isBefore(eveningEnd))
}

private fun formatTime(time: LocalTime): String = time.format(timeFormatter)

private fun formatDuration(duration: Duration): String {
    val hours = duration.toHours()
    val minutes = duration.toMinutes() % 60
    return buildString {
        if (hours > 0) append("${hours}h ")
        append("${minutes}m")
    }
}