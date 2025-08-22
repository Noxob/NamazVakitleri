package com.noxob.namazvakti.tile

import android.content.Context
import android.graphics.Color
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
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import com.noxob.namazvakti.computeTimes
import com.noxob.namazvakti.prayerWindow
import com.noxob.namazvakti.kerahatInterval
import com.noxob.namazvakti.formatTime
import com.noxob.namazvakti.resolveCity

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
    val state = runBlocking { loadState(context) }
    val singleTileTimeline = TimelineBuilders.Timeline.Builder()
        .addTimelineEntry(
            TimelineBuilders.TimelineEntry.Builder()
                .setLayout(
                    LayoutElementBuilders.Layout.Builder()
                        .setRoot(tileLayout(requestParams, context, state))
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

data class TileState(
    val city: String,
    val nextName: String,
    val remaining: Duration,
    val times: List<Pair<String, LocalTime>>,
    val isKerahat: Boolean
)

private suspend fun loadState(context: Context): TileState {
    val fused = LocationServices.getFusedLocationProviderClient(context)
    val loc = try { fused.lastLocation.await() } catch (e: Exception) { null }
    val lat = loc?.latitude ?: 39.91987
    val lng = loc?.longitude ?: 32.85427
    val today = computeTimes(lat, lng, LocalDate.now())
    val tomorrow = computeTimes(lat, lng, LocalDate.now().plusDays(1))
    val yesterday = computeTimes(lat, lng, LocalDate.now().minusDays(1))
    val now = LocalDateTime.now()
    val (name, _, end) = prayerWindow(now, yesterday, today, tomorrow)
    val kerahat = kerahatInterval(now, today)
    val city = resolveCity(context, lat, lng)
    val times = listOf(
        "Fajr" to today[0],
        "Sunrise" to today[1],
        "Dhuhr" to today[2],
        "Asr" to today[3],
        "Maghrib" to today[4],
        "Isha" to today[5]
    )
    val remaining = Duration.between(now, end)
    return TileState(city, name, remaining, times, kerahat != null)
}

private fun tileLayout(
    requestParams: RequestBuilders.TileRequest,
    context: Context,
    state: TileState
): LayoutElementBuilders.LayoutElement {
    val column = LayoutElementBuilders.Column.Builder()
    column.addContent(
        Text.Builder(context, state.city)
            .setTypography(Typography.TYPOGRAPHY_CAPTION2)
            .setColor(argb(Colors.DEFAULT.onSurface))
            .build()
    )
    val hrs = state.remaining.toHours()
    val mins = state.remaining.minusHours(hrs).toMinutes()
    column.addContent(
        Text.Builder(context, "${state.nextName} ${hrs}h ${mins}m")
            .setColor(argb(Colors.DEFAULT.onSurface))
            .setTypography(Typography.TYPOGRAPHY_CAPTION1)
            .build()
    )
    state.times.forEach { (name, time) ->
        column.addContent(
            Text.Builder(context, "$name ${formatTime(time)}")
                .setColor(argb(Colors.DEFAULT.onSurface))
                .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                .build()
        )
    }
    if (state.isKerahat) {
        column.addContent(
            Text.Builder(context, "Kerahat")
                .setColor(argb(Color.RED))
                .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                .build()
        )
    }
    return PrimaryLayout.Builder(requestParams.deviceConfiguration)
        .setResponsiveContentInsetEnabled(true)
        .setContent(column.build())
        .build()
}

@Preview(device = WearDevices.SMALL_ROUND)
@Preview(device = WearDevices.LARGE_ROUND)
fun tilePreview(context: Context) = TilePreviewData(::resources) {
    tile(it, context)
}