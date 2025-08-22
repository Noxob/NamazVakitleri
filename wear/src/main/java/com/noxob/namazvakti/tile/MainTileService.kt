package com.noxob.namazvakti.tile

import android.content.Context
import android.graphics.Color
import android.location.Geocoder
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.tooling.preview.Preview
import androidx.wear.tiles.tooling.preview.TilePreviewData
import androidx.wear.tooling.preview.devices.WearDevices
import com.google.android.gms.location.LocationServices
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import com.noxob.namazvakti.PrayerUtils

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
    val info = loadInfo(context)
    val singleTileTimeline = TimelineBuilders.Timeline.Builder()
        .addTimelineEntry(
            TimelineBuilders.TimelineEntry.Builder()
                .setLayout(
                    LayoutElementBuilders.Layout.Builder()
                        .setRoot(tileLayout(requestParams, context, info))
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
    info: PrayerInfo,
): LayoutElementBuilders.LayoutElement {
    val now = LocalDateTime.now()
    val remaining = Duration.between(now, info.nextTime)
    val timesText = PrayerUtils.names().mapIndexed { index, n ->
        "${n.take(2)} ${PrayerUtils.formatTime(info.todayTimes[index])}"
    }.joinToString(" ")
    val column = LayoutElementBuilders.Column.Builder()
        .addContent(
            Text.Builder(context, info.city)
                .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                .build()
        )
        .addContent(
            Text.Builder(context, "${info.nextName} ${PrayerUtils.formatDuration(remaining)}")
                .setTypography(Typography.TYPOGRAPHY_TITLE3)
                .setColor(argb(if (info.isKerahat) Color.RED else Colors.DEFAULT.onSurface))
                .build()
        )
        .addContent(
            Text.Builder(context, timesText)
                .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                .build()
        )
        .build()

    return PrimaryLayout.Builder(requestParams.deviceConfiguration)
        .setResponsiveContentInsetEnabled(true)
        .setContent(column)
        .build()
}

private suspend fun loadInfo(context: Context): PrayerInfo {
    val (lat, lng) = getLocation(context)
    val today = PrayerUtils.computePrayerTimes(lat, lng, LocalDate.now())
    val tomorrow = PrayerUtils.computePrayerTimes(lat, lng, LocalDate.now().plusDays(1))
    val now = LocalDateTime.now()
    val (name, time) = PrayerUtils.nextPrayer(now, today, tomorrow)
    val isKerahat = PrayerUtils.kerahatInterval(now, today) != null
    val city = getCity(context, lat, lng)
    return PrayerInfo(city, name, time, today, isKerahat)
}

private suspend fun getLocation(context: Context): Pair<Double, Double> = withContext(Dispatchers.IO) {
    val client = LocationServices.getFusedLocationProviderClient(context)
    val loc = client.lastLocation.await()
    if (loc != null) loc.latitude to loc.longitude else 39.91987 to 32.85427
}

private suspend fun getCity(context: Context, lat: Double, lng: Double): String = withContext(Dispatchers.IO) {
    return@withContext try {
        val geocoder = Geocoder(context, Locale.getDefault())
        val list = geocoder.getFromLocation(lat, lng, 1)
        list?.firstOrNull()?.locality ?: ""
    } catch (e: Exception) {
        ""
    }
}

private data class PrayerInfo(
    val city: String,
    val nextName: String,
    val nextTime: LocalDateTime,
    val todayTimes: List<java.time.LocalTime>,
    val isKerahat: Boolean,
)

@Preview(device = WearDevices.SMALL_ROUND)
@Preview(device = WearDevices.LARGE_ROUND)
fun tilePreview(context: Context) = TilePreviewData(::resources) {
    kotlinx.coroutines.runBlocking { tile(it, context) }
}