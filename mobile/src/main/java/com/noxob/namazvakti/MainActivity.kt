package com.noxob.namazvakti

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var nextPrayer: TextView
    private lateinit var otherTimes: TextView
    private lateinit var kerahat: TextView
    private lateinit var cityView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nextPrayer = findViewById(R.id.next_prayer)
        otherTimes = findViewById(R.id.other_times)
        kerahat = findViewById(R.id.kerahat)
        cityView = findViewById(R.id.city)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 0)
        } else {
            loadData()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadData()
        }
    }

    private fun loadData() {
        lifecycleScope.launch {
            val (lat, lng) = getLocation()
            val today = PrayerUtils.computePrayerTimes(lat, lng, LocalDate.now())
            val tomorrow = PrayerUtils.computePrayerTimes(lat, lng, LocalDate.now().plusDays(1))
            val now = LocalDateTime.now()
            val (name, time) = PrayerUtils.nextPrayer(now, today, tomorrow)
            val kerahatInterval = PrayerUtils.kerahatInterval(now, today)
            val city = getCity(lat, lng)

            val remaining = Duration.between(now, time)
            nextPrayer.text = "Next: $name (${PrayerUtils.formatDuration(remaining)})"

            val timesText = PrayerUtils.names().mapIndexed { index, n ->
                "$n ${PrayerUtils.formatTime(today[index])}"
            }.joinToString("\n")
            otherTimes.text = timesText

            kerahat.text = if (kerahatInterval != null) "Kerahat" else ""
            cityView.text = city
        }
    }

    private suspend fun getLocation(): Pair<Double, Double> = withContext(Dispatchers.IO) {
        val client = LocationServices.getFusedLocationProviderClient(this@MainActivity)
        val loc = client.lastLocation.await()
        if (loc != null) loc.latitude to loc.longitude else 39.91987 to 32.85427
    }

    private suspend fun getCity(lat: Double, lng: Double): String = withContext(Dispatchers.IO) {
        return@withContext try {
            val geocoder = Geocoder(this@MainActivity, Locale.getDefault())
            val list = geocoder.getFromLocation(lat, lng, 1)
            list?.firstOrNull()?.locality ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}

