package com.noxob.namazvakti.tile

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.util.Log
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
import com.google.android.gms.wearable.Wearable
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService
import com.noxob.namazvakti.PrayerUtils
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.tasks.await

private const val RESOURCES_VERSION = "0"

@OptIn(ExperimentalHorologistApi::class)
class MainTileService : SuspendingTileService() {

    private val dataClient by lazy { Wearable.getDataClient(this) }
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("prayer_cache", MODE_PRIVATE)
    }

    override suspend fun resourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ) = resources()

    override suspend fun tileRequest(
        requestParams: RequestBuilders.TileRequest
    ) = tile(requestParams)

    private fun resources(): ResourceBuilders.Resources {
        return ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .build()
    }

    private suspend fun tile(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        val (lat, lng) = getLocation()
        val today = LocalDate.now()
        val timesToday = PrayerUtils.computeTimes(lat, lng, today)
        val timesYesterday = PrayerUtils.computeTimes(lat, lng, today.minusDays(1))
        val timesTomorrow = PrayerUtils.computeTimes(lat, lng, today.plusDays(1))
        val now = LocalDateTime.now()
        val (nextName, _, end) = PrayerUtils.prayerWindow(now, timesYesterday, timesToday, timesTomorrow)
        val remaining = Duration.between(now, end)
        val hours = remaining.toHours()
        val minutes = remaining.toMinutes() % 60
        val countdown = if (hours > 0) "${'$'}hours s ${'$'}minutes d" else "${'$'}minutes d"
        val kerahat = PrayerUtils.kerahatInterval(now, timesToday) != null
        val city = withContext(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(this@MainTileService, Locale.getDefault())
                val list = geocoder.getFromLocation(lat, lng, 1)
                list?.firstOrNull()?.locality
            } catch (e: Exception) {
                null
            }
        } ?: ""

        val layout = PrimaryLayout.Builder(requestParams.deviceConfiguration)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelText(city)
            .setContent(
                Text.Builder(this, "$nextName $countdown")
                    .setColor(argb(Colors.DEFAULT.onSurface))
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .build()
            )
            .setSecondaryLabelText(if (kerahat) "Kerahat" else "")
            .build()

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

    private suspend fun getLocation(): Pair<Double, Double> {
        val watchLocation = if (
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            try {
                withTimeoutOrNull(2000) { fusedClient.lastLocation.await() }
            } catch (e: Exception) {
                Log.w("MainTile", "Watch location unavailable", e)
                null
            }
        } else null

        val locFromWatch = watchLocation?.let { it.latitude to it.longitude }
        val locFromPhone = locFromWatch ?: run {
            val uri = Uri.parse("wear://*/location")
            val buffer = try {
                withTimeoutOrNull(2000) { dataClient.getDataItems(uri).await() }
            } catch (e: Exception) {
                Log.e("MainTile", "Error reading location", e)
                null
            }
            buffer?.use { buf ->
                if (buf.count > 0) {
                    val map = com.google.android.gms.wearable.DataMapItem.fromDataItem(buf[0]).dataMap
                    map.getDouble("lat") to map.getDouble("lng")
                } else null
            }
        }

        val finalLoc = locFromPhone ?: loadLastLocation() ?: (39.91987 to 32.85427)
        saveLastLocation(finalLoc.first, finalLoc.second)
        return finalLoc
    }

    private fun saveLastLocation(lat: Double, lng: Double) {
        prefs.edit().putFloat("last_lat", lat.toFloat()).putFloat("last_lng", lng.toFloat()).apply()
    }

    private fun loadLastLocation(): Pair<Double, Double>? {
        if (!prefs.contains("last_lat") || !prefs.contains("last_lng")) return null
        return prefs.getFloat("last_lat", 0f).toDouble() to prefs.getFloat("last_lng", 0f).toDouble()
    }
}

@Preview(device = WearDevices.SMALL_ROUND)
@Preview(device = WearDevices.LARGE_ROUND)
fun tilePreview(context: Context) = TilePreviewData(
    { ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build() }
) {
    TileBuilders.Tile.Builder()
        .setResourcesVersion(RESOURCES_VERSION)
        .setTileTimeline(
            TimelineBuilders.Timeline.Builder()
                .addTimelineEntry(
                    TimelineBuilders.TimelineEntry.Builder()
                        .setLayout(
                            LayoutElementBuilders.Layout.Builder()
                                .setRoot(
                                    PrimaryLayout.Builder(it.deviceConfiguration)
                                        .setContent(
                                            Text.Builder(context, "Preview")
                                                .setColor(argb(Colors.DEFAULT.onSurface))
                                                .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                                                .build()
                                        )
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
                .build()
        )
        .build()
}
