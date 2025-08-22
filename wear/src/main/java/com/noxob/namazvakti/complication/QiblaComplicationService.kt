package com.noxob.namazvakti.complication

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.location.LocationServices
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.noxob.namazvakti.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class QiblaComplicationService : SuspendingComplicationDataSourceService() {

    private val dataClient by lazy { Wearable.getDataClient(this) }
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

    companion object {
        private const val TAG = "QiblaComplication"
        private const val KAABA_LAT = 21.422487
        private const val KAABA_LNG = 39.826206
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val icon = Icon.createWithResource(this, R.drawable.ic_qibla_pointer)
        val mono = MonochromaticImage.Builder(icon).build()
        val desc = PlainComplicationText.Builder("Qibla").build()
        return when (type) {
            ComplicationType.MONOCHROMATIC_IMAGE ->
                MonochromaticImageComplicationData.Builder(mono, desc).build()
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(desc, desc).setMonochromaticImage(mono).build()
            else -> null
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        return try {
            val (lat, lng) = getLocation()
            val bearing = qiblaBearing(lat, lng)
            val heading = getHeading()
            val diff = ((bearing - heading + 360) % 360)
            val icon = rotatedIcon(diff)
            val aligned = diff < 10 || diff > 350
            if (aligned) icon.setTint(Color.GREEN)
            val mono = MonochromaticImage.Builder(icon).build()
            val desc = PlainComplicationText.Builder("Qibla").build()
            val data = when (request.complicationType) {
                ComplicationType.MONOCHROMATIC_IMAGE ->
                    MonochromaticImageComplicationData.Builder(mono, desc).build()
                ComplicationType.SHORT_TEXT ->
                    ShortTextComplicationData.Builder(desc, desc).setMonochromaticImage(mono).build()
                else ->
                    MonochromaticImageComplicationData.Builder(mono, desc).build()
            }
            scheduleUpdate()
            data
        } catch (e: Exception) {
            Log.e(TAG, "Error creating qibla complication", e)
            val icon = Icon.createWithResource(this, R.drawable.ic_qibla_pointer)
            val mono = MonochromaticImage.Builder(icon).build()
            val desc = PlainComplicationText.Builder("Qibla").build()
            MonochromaticImageComplicationData.Builder(mono, desc).build()
        }
    }

    private fun qiblaBearing(lat: Double, lng: Double): Float {
        val phiK = Math.toRadians(KAABA_LAT)
        val lambdaK = Math.toRadians(KAABA_LNG)
        val phi = Math.toRadians(lat)
        val lambda = Math.toRadians(lng)
        val dLambda = lambdaK - lambda
        val y = sin(dLambda)
        val x = cos(phi) * tan(phiK) - sin(phi) * cos(dLambda)
        val bearing = Math.toDegrees(atan2(y, x))
        return ((bearing + 360) % 360).toFloat()
    }

    private suspend fun getHeading(): Float = suspendCoroutine { cont ->
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val rot = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rot, event.values)
                val orient = FloatArray(3)
                SensorManager.getOrientation(rot, orient)
                val azimuth = Math.toDegrees(orient[0].toDouble()).toFloat()
                sm.unregisterListener(this)
                cont.resume((azimuth + 360) % 360)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun rotatedIcon(angle: Float): Icon {
        val drawable = resources.getDrawable(R.drawable.ic_qibla_pointer, null)
        val bmp = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = drawable.intrinsicWidth / 2f
        val cy = drawable.intrinsicHeight / 2f
        canvas.save()
        canvas.rotate(angle, cx, cy)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        canvas.restore()
        return Icon.createWithBitmap(bmp)
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

        locFromPhone ?: (39.91987 to 32.85427)
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
}
