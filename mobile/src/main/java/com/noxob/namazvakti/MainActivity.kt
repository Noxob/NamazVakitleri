package com.noxob.namazvakti

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import com.batoulapps.adhan2.CalculationMethod
import com.batoulapps.adhan2.Coordinates
import com.batoulapps.adhan2.Madhab
import com.batoulapps.adhan2.PrayerTimes
import com.batoulapps.adhan2.data.DateComponents
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import kotlinx.datetime.toJavaInstant

class MainActivity : AppCompatActivity() {

    private val locationSender by lazy { LocationSender(this) { location ->
        displayPrayerTimes(location.latitude, location.longitude)
    } }

    private lateinit var fajrView: TextView
    private lateinit var sunriseView: TextView
    private lateinit var dhuhrView: TextView
    private lateinit var asrView: TextView
    private lateinit var maghribView: TextView
    private lateinit var ishaView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        fajrView = findViewById(R.id.fajr_time)
        sunriseView = findViewById(R.id.sunrise_time)
        dhuhrView = findViewById(R.id.dhuhr_time)
        asrView = findViewById(R.id.asr_time)
        maghribView = findViewById(R.id.maghrib_time)
        ishaView = findViewById(R.id.isha_time)

        if (hasLocationPermission()) {
            Log.d("MainActivity", "Location permission already granted")
            locationSender.sendLastLocation()
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
            locationSender.sendLastLocation()
        } else {
            Log.d("MainActivity", "Location permission denied")
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun displayPrayerTimes(lat: Double, lng: Double) {
        val times = computeTimes(lat, lng)
        val views = listOf(fajrView, sunriseView, dhuhrView, asrView, maghribView, ishaView)
        val names = listOf("Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha")
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        for (i in views.indices) {
            views[i].text = "${names[i]}: ${times[i].format(formatter)}"
        }
    }

    private fun computeTimes(lat: Double, lng: Double): List<LocalTime> {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val methodName = prefs.getString("method", CalculationMethod.TURKEY.name)!!
        val madhabName = prefs.getString("madhab", Madhab.SHAFI.name)!!
        val method = CalculationMethod.valueOf(methodName)
        val params = method.parameters.copy(madhab = Madhab.valueOf(madhabName))
        val date = LocalDate.now()
        val coordinates = Coordinates(lat, lng)
        val components = DateComponents(date.year, date.monthValue, date.dayOfMonth)
        val times = PrayerTimes(coordinates, components, params)
        val offsetMinutes = TimeZone.getDefault().rawOffset / 60000
        val zoneOffset = ZoneOffset.ofTotalSeconds(offsetMinutes * 60)
        return listOf(
            times.fajr,
            times.sunrise,
            times.dhuhr,
            times.asr,
            times.maghrib,
            times.isha
        ).map { it.toJavaInstant().atOffset(zoneOffset).toLocalTime() }
    }
}
