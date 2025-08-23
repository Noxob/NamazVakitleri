package com.noxob.namazvakti.complication

import android.Manifest
import android.content.ComponentName
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.Context.SENSOR_SERVICE
import android.graphics.*
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.google.android.gms.location.LocationServices
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.*
import java.time.Instant

class QiblaComplicationService : SuspendingComplicationDataSourceService() {

    private val dataClient by lazy { Wearable.getDataClient(this) }
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("prayer_cache", MODE_PRIVATE)
    }

    companion object {
        private const val TAG = "QiblaComplication"
        private const val LAST_LAT = "last_lat"
        private const val LAST_LNG = "last_lng"
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val icon = buildIcon(0f, true)
        val text = PlainComplicationText.Builder("Qibla").build()
        val range = TimeRange.between(Instant.MIN, Instant.MAX)
        return when (type) {
            ComplicationType.SMALL_IMAGE -> {
                val small = SmallImage.Builder(icon, SmallImageType.ICON).build()
                SmallImageComplicationData.Builder(small, text)
                    .setValidTimeRange(range)
                    .build()
            }
            else -> {
                val mono = MonochromaticImage.Builder(icon).build()
                MonochromaticImageComplicationData.Builder(mono, text)
                    .setValidTimeRange(range)
                    .build()
            }
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        return try {
            val (lat, lng) = getLocation()
            val qibla = qiblaDirection(lat, lng)
            val (heading, calibrated) = getHeading()
            val diff = ((qibla - heading + 360) % 360)
            val aligned = abs(((qibla - heading + 540) % 360) - 180) < 5
            val icon = buildIcon(diff, aligned, calibrated)
            val desc = PlainComplicationText.Builder("Qibla direction").build()
            val range = TimeRange.between(Instant.now(), Instant.now().plusSeconds(1))
            val data = when (request.complicationType) {
                ComplicationType.SMALL_IMAGE -> {
                    val small = SmallImage.Builder(icon, SmallImageType.ICON).build()
                    SmallImageComplicationData.Builder(small, desc)
                        .setValidTimeRange(range)
                        .build()
                }
                ComplicationType.MONOCHROMATIC_IMAGE -> {
                    val mono = MonochromaticImage.Builder(icon).build()
                    MonochromaticImageComplicationData.Builder(mono, desc)
                        .setValidTimeRange(range)
                        .build()
                }
                else -> {
                    val text = PlainComplicationText.Builder("${diff.roundToInt()}°").build()
                    val mono = MonochromaticImage.Builder(icon).build()
                    ShortTextComplicationData.Builder(text, desc)
                        .setMonochromaticImage(mono)
                        .setValidTimeRange(range)
                        .build()
                }
            }
            scheduleUpdate()
            data
        } catch (e: Exception) {
            Log.e(TAG, "Error creating qibla complication", e)
            val icon = buildIcon(0f, false)
            val desc = PlainComplicationText.Builder("Qibla direction").build()
            MonochromaticImageComplicationData.Builder(
                MonochromaticImage.Builder(icon).build(),
                desc
            ).setValidTimeRange(TimeRange.between(Instant.now(), Instant.now().plusSeconds(30))).build()
        }
    }

    private fun scheduleUpdate() {
        val requester = ComplicationDataSourceUpdateRequester.create(
            this,
            ComponentName(this, QiblaComplicationService::class.java)
        )
        Handler(Looper.getMainLooper()).postDelayed({
            requester.requestUpdateAll()
        }, 1000)
    }

    private suspend fun getLocation(): Pair<Double, Double> = withContext(Dispatchers.IO) {
        val watchLocation = if (
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            try { withTimeoutOrNull(2000) { fusedClient.lastLocation.await() } } catch (e: Exception) {
                Log.w(TAG, "Watch location unavailable", e)
                null
            }
        } else null

        val locFromWatch = watchLocation?.let { it.latitude to it.longitude }

        val locFromPhone = locFromWatch ?: run {
            val uri = Uri.parse("wear://*/location")
            val buffer = try { withTimeoutOrNull(2000) { dataClient.getDataItems(uri).await() } } catch (e: Exception) {
                Log.e(TAG, "Error reading location", e)
                null
            }
            buffer?.use { buf ->
                if (buf.count > 0) {
                    val map = DataMapItem.fromDataItem(buf[0]).dataMap
                    map.getDouble("lat") to map.getDouble("lng")
                } else null
            }
        }

        val finalLoc = locFromPhone ?: loadLastLocation() ?: 39.91987 to 32.85427
        saveLastLocation(finalLoc.first, finalLoc.second)
        finalLoc
    }

    private suspend fun getHeading(): Pair<Float, Boolean> = suspendCancellableCoroutine { cont ->
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            cont.resume(0f to false)
            return@suspendCancellableCoroutine
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val rot = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rot, event.values)
                val orient = FloatArray(3)
                SensorManager.getOrientation(rot, orient)
                var azimuth = Math.toDegrees(orient[0].toDouble()).toFloat()
                azimuth = (azimuth + 360) % 360
                sensorManager.unregisterListener(this)
                val calibrated = event.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
                cont.resume(azimuth to calibrated)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        cont.invokeOnCancellation { sensorManager.unregisterListener(listener) }
    }

    private fun qiblaDirection(lat: Double, lng: Double): Float {
        val kaabaLat = Math.toRadians(21.422487)
        val kaabaLng = Math.toRadians(39.826206)
        val latRad = Math.toRadians(lat)
        val lngRad = Math.toRadians(lng)
        val dLng = kaabaLng - lngRad
        val y = sin(dLng)
        val x = cos(latRad) * tan(kaabaLat) - sin(latRad) * cos(dLng)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
    }

    private fun buildIcon(diff: Float, aligned: Boolean, calibrated: Boolean = true): Icon {
        val size = 48
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val color = when {
            !calibrated -> Color.RED
            aligned -> Color.GREEN
            else -> Color.WHITE
        }
        val paint = Paint().apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        val center = size / 2f
        val radius = center - 4f
        canvas.drawCircle(center, center, radius, paint)
        val angle = Math.toRadians(diff.toDouble())
        val dotX = center + (radius - 6) * sin(angle)
        val dotY = center - (radius - 6) * cos(angle)
        val dotPaint = Paint().apply {
            this.color = color
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(dotX.toFloat(), dotY.toFloat(), 5f, dotPaint)
        return Icon.createWithBitmap(bitmap)
    }

    private fun saveLastLocation(lat: Double, lng: Double) {
        prefs.edit().putFloat(LAST_LAT, lat.toFloat()).putFloat(LAST_LNG, lng.toFloat()).apply()
    }

    private fun loadLastLocation(): Pair<Double, Double>? {
        if (!prefs.contains(LAST_LAT) || !prefs.contains(LAST_LNG)) return null
        return prefs.getFloat(LAST_LAT, 0f).toDouble() to prefs.getFloat(LAST_LNG, 0f).toDouble()
    }
}

