package com.noxob.namazvakti.complication

import android.content.ComponentName
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.noxob.namazvakti.R
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * Complication that shows the direction of the Kaaba using the watch's compass sensor.
 * The dot on the icon rotates towards the Qibla. When the watch is aligned within
 * a small threshold the icon turns green.
 */
class QiblaCompassComplicationService : SuspendingComplicationDataSourceService() {

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("prayer_cache", MODE_PRIVATE)
    }

    companion object {
        private const val TAG = "QiblaComplication"
        private const val LAST_LAT = "last_lat"
        private const val LAST_LNG = "last_lng"
        private const val QIBLA_LAT = 21.422487
        private const val QIBLA_LNG = 39.826206
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val icon = Icon.createWithResource(this, R.drawable.ic_qibla_compass)
        val mono = MonochromaticImage.Builder(icon).build()
        val desc = PlainComplicationText.Builder("Qibla direction").build()
        return if (type == ComplicationType.MONOCHROMATIC_IMAGE) {
            MonochromaticImageComplicationData.Builder(mono, desc).build()
        } else null
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        return try {
            val (lat, lng) = loadLastLocation() ?: (39.91987 to 32.85427)
            val qibla = bearingToQibla(lat, lng)
            val heading = getHeading()
            val delta = ((qibla - heading + 360f) % 360f)
            val icon = rotatedIcon(delta, delta < 5f || delta > 355f)
            val mono = MonochromaticImage.Builder(icon).build()
            val desc = PlainComplicationText.Builder("Qibla $qibla°").build()
            scheduleComplicationUpdate(System.currentTimeMillis() + 1000)
            MonochromaticImageComplicationData.Builder(mono, desc).build()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating complication", e)
            val icon = Icon.createWithResource(this, R.drawable.ic_qibla_compass)
            val mono = MonochromaticImage.Builder(icon).build()
            val desc = PlainComplicationText.Builder("Qibla").build()
            MonochromaticImageComplicationData.Builder(mono, desc).build()
        }
    }

    private fun bearingToQibla(lat: Double, lng: Double): Float {
        val phi1 = Math.toRadians(lat)
        val phi2 = Math.toRadians(QIBLA_LAT)
        val dLambda = Math.toRadians(QIBLA_LNG - lng)
        val y = sin(dLambda)
        val x = cos(phi1) * tan(phi2) - sin(phi1) * cos(dLambda)
        val brng = Math.toDegrees(atan2(y, x))
        return ((brng + 360) % 360).toFloat()
    }

    private suspend fun getHeading(): Float = suspendCancellableCoroutine { cont ->
        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnet = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val accelValues = FloatArray(3)
        val magnetValues = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> System.arraycopy(event.values, 0, accelValues, 0, 3)
                    Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, magnetValues, 0, 3)
                }
                if (accelValues.any { it != 0f } && magnetValues.any { it != 0f }) {
                    val R = FloatArray(9)
                    val I = FloatArray(9)
                    if (SensorManager.getRotationMatrix(R, I, accelValues, magnetValues)) {
                        val orientation = FloatArray(3)
                        SensorManager.getOrientation(R, orientation)
                        var azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                        if (azimuth < 0) azimuth += 360f
                        cont.resume(azimuth) {}
                        sensorManager.unregisterListener(this)
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, accel, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(listener, magnet, SensorManager.SENSOR_DELAY_FASTEST)
        cont.invokeOnCancellation { sensorManager.unregisterListener(listener) }
    }

    private fun rotatedIcon(angle: Float, isAligned: Boolean): Icon {
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_qibla_compass)!!.mutate()
        val color = if (isAligned) Color.GREEN else Color.WHITE
        drawable.setTint(color)
        val width = drawable.intrinsicWidth
        val height = drawable.intrinsicHeight
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.rotate(angle, width / 2f, height / 2f)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        canvas.restore()
        return Icon.createWithBitmap(bitmap)
    }

    private fun loadLastLocation(): Pair<Double, Double>? {
        if (!prefs.contains(LAST_LAT) || !prefs.contains(LAST_LNG)) return null
        return prefs.getFloat(LAST_LAT, 0f).toDouble() to prefs.getFloat(LAST_LNG, 0f).toDouble()
    }

    private fun scheduleComplicationUpdate(triggerAtMillis: Long) {
        val requester = ComplicationDataSourceUpdateRequester.create(
            this,
            ComponentName(this, QiblaCompassComplicationService::class.java)
        )
        val delay = max(0L, triggerAtMillis - System.currentTimeMillis())
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({ requester.requestUpdateAll() }, delay)
    }
}

