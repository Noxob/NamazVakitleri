package com.noxob.namazvakti.complication

import android.Manifest
import android.content.ComponentName
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.Context.SENSOR_SERVICE
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.google.android.gms.location.LocationServices
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

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
        private const val KAABA_LAT = 21.4225
        private const val KAABA_LNG = 39.8262
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val icon = createCompassIcon(0f, false)
        val image = MonochromaticImage.Builder(icon).build()
        val desc = PlainComplicationText.Builder("Kıble").build()
        return MonochromaticImageComplicationData.Builder(image, desc).build()
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        return try {
            val (lat, lng) = getLocation()
            val qibla = computeQiblaDirection(lat, lng)
            val heading = getHeading()
            val diff = ((qibla - heading + 360f) % 360f)
            val aligned = diff < 5f || diff > 355f
            val icon = createCompassIcon(diff, aligned)
            val image = MonochromaticImage.Builder(icon).build()
            val desc = PlainComplicationText.Builder("Kıble yönü").build()
            val data = MonochromaticImageComplicationData.Builder(image, desc).build()
            scheduleComplicationUpdate(System.currentTimeMillis() + 1000)
            data
        } catch (e: Exception) {
            Log.e(TAG, "Error creating qibla complication", e)
            val icon = createCompassIcon(0f, false)
            val image = MonochromaticImage.Builder(icon).build()
            val desc = PlainComplicationText.Builder("Kıble").build()
            MonochromaticImageComplicationData.Builder(image, desc).build()
        }
    }

    private fun createCompassIcon(angle: Float, aligned: Boolean): Icon {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val center = size / 2f
        val stroke = Paint().apply {
            color = if (aligned) Color.GREEN else Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        canvas.drawCircle(center, center, center - 4f, stroke)
        val dotPaint = Paint().apply {
            color = if (aligned) Color.GREEN else Color.RED
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val rad = Math.toRadians(angle.toDouble())
        val radius = center * 0.8
        val x = center + radius * sin(rad)
        val y = center - radius * cos(rad)
        canvas.drawCircle(x.toFloat(), y.toFloat(), 6f, dotPaint)
        return Icon.createWithBitmap(bmp)
    }

    private fun computeQiblaDirection(lat: Double, lng: Double): Float {
        val kaabaLatRad = Math.toRadians(KAABA_LAT)
        val kaabaLngRad = Math.toRadians(KAABA_LNG)
        val latRad = Math.toRadians(lat)
        val lngRad = Math.toRadians(lng)
        val dLng = kaabaLngRad - lngRad
        val y = sin(dLng)
        val x = cos(latRad) * tan(kaabaLatRad) - sin(latRad) * cos(dLng)
        var angle = Math.toDegrees(atan2(y, x))
        if (angle < 0) angle += 360.0
        return angle.toFloat()
    }

    private suspend fun getHeading(): Float = suspendCancellableCoroutine { cont ->
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            cont.resume(0f)
            return@suspendCancellableCoroutine
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val rot = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rot, event.values)
                val orient = FloatArray(3)
                SensorManager.getOrientation(rot, orient)
                var azimuth = Math.toDegrees(orient[0].toDouble()).toFloat()
                if (azimuth < 0) azimuth += 360f
                sensorManager.unregisterListener(this)
                if (cont.isActive) cont.resume(azimuth)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        cont.invokeOnCancellation { sensorManager.unregisterListener(listener) }
    }

    private suspend fun getLocation(): Pair<Double, Double> = withContext(Dispatchers.IO) {
        val watchLocation = if (
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            try {
                withTimeoutOrNull(2000) { fusedClient.lastLocation.await() }
            } catch (e: Exception) {
                null
            }
        } else null

        val locFromWatch = watchLocation?.let { it.latitude to it.longitude }

        val locFromPhone = locFromWatch ?: run {
            val uri = Uri.parse("wear://*/location")
            val buffer = try {
                withTimeoutOrNull(2000) { dataClient.getDataItems(uri).await() }
            } catch (e: Exception) {
                null
            }

            buffer?.use { buf ->
                if (buf.count > 0) {
                    val map = DataMapItem.fromDataItem(buf[0]).dataMap
                    map.getDouble("lat") to map.getDouble("lng")
                } else null
            }
        }

        val finalLoc = locFromPhone ?: loadLastLocation() ?: (39.91987 to 32.85427)

        saveLastLocation(finalLoc.first, finalLoc.second)
        finalLoc
    }

    private fun saveLastLocation(lat: Double, lng: Double) {
        prefs.edit().putFloat(LAST_LAT, lat.toFloat()).putFloat(LAST_LNG, lng.toFloat()).apply()
    }

    private fun loadLastLocation(): Pair<Double, Double>? {
        if (!prefs.contains(LAST_LAT) || !prefs.contains(LAST_LNG)) return null
        return prefs.getFloat(LAST_LAT, 0f).toDouble() to prefs.getFloat(LAST_LNG, 0f).toDouble()
    }

    private fun scheduleComplicationUpdate(triggerAtMillis: Long) {
        val delay = kotlin.math.max(0L, triggerAtMillis - System.currentTimeMillis())
        val requester = ComplicationDataSourceUpdateRequester.create(
            this,
            ComponentName(this, QiblaComplicationService::class.java)
        )
        Handler(Looper.getMainLooper()).postDelayed({
            requester.requestUpdateAll()
        }, delay)
    }
}
