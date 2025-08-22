package com.noxob.namazvakti

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.location.Geocoder
import android.location.Location
import android.util.Log
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val locationSender by lazy { LocationSender(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        if (hasLocationPermission()) {
            Log.d("MainActivity", "Location permission already granted")
            locationSender.sendLastLocation { updatePrayerInfo(it) }
        } else {
            Log.d("MainActivity", "Requesting location permission")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                0
            )
        }
    }

    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d("MainActivity", "Location permission granted")
            locationSender.sendLastLocation { updatePrayerInfo(it) }
        } else {
            Log.d("MainActivity", "Location permission denied")
        }
    }

    private fun updatePrayerInfo(location: Location) {
        val today = LocalDate.now()
        val timesToday = PrayerUtils.computeTimes(location.latitude, location.longitude, today)
        val timesYesterday = PrayerUtils.computeTimes(location.latitude, location.longitude, today.minusDays(1))
        val timesTomorrow = PrayerUtils.computeTimes(location.latitude, location.longitude, today.plusDays(1))
        val now = LocalDateTime.now()
        val (nextName, _, end) = PrayerUtils.prayerWindow(now, timesYesterday, timesToday, timesTomorrow)
        val remaining = Duration.between(now, end)
        val hours = remaining.toHours()
        val minutes = remaining.toMinutes() % 60
        val countdown = if (hours > 0) "${'$'}hours s ${'$'}minutes d" else "${'$'}minutes d"

        val kerahat = PrayerUtils.kerahatInterval(now, timesToday) != null

        runOnUiThread {
            findViewById<TextView>(R.id.tv_next).text = "Next: ${'$'}nextName (${countdown})"
            findViewById<TextView>(R.id.tv_kerahat).text = if (kerahat) "Kerahat: Yes" else "Kerahat: No"
            val names = listOf(R.id.tv_fajr, R.id.tv_sunrise, R.id.tv_dhuhr, R.id.tv_asr, R.id.tv_maghrib, R.id.tv_isha)
            val labels = listOf("Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha")
            for (i in timesToday.indices) {
                val t = timesToday[i]
                val text = String.format(Locale.getDefault(), "%02d:%02d", t.hour, t.minute)
                findViewById<TextView>(names[i]).text = "${'$'}{labels[i]}: ${'$'}text"
            }
            Thread {
                try {
                    val geocoder = Geocoder(this, Locale.getDefault())
                    val list = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    val city = list?.firstOrNull()?.locality ?: "Unknown"
                    runOnUiThread {
                        findViewById<TextView>(R.id.tv_city).text = "City: ${'$'}city"
                    }
                } catch (_: Exception) {
                }
            }.start()
        }
    }
}