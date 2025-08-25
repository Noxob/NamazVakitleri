package com.noxob.namazvakti

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import com.batoulapps.adhan2.CalculationMethod
import com.batoulapps.adhan2.Coordinates
import com.batoulapps.adhan2.Madhab
import com.batoulapps.adhan2.PrayerTimes
import com.batoulapps.adhan2.data.DateComponents
import com.google.android.gms.location.LocationServices
import kotlinx.datetime.toJavaInstant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private val locationSender by lazy { LocationSender(this) }
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private lateinit var fajrView: TextView
    private lateinit var sunriseView: TextView
    private lateinit var dhuhrView: TextView
    private lateinit var asrView: TextView
    private lateinit var maghribView: TextView
    private lateinit var ishaView: TextView

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        fajrView = findViewById(R.id.tv_fajr)
        sunriseView = findViewById(R.id.tv_sunrise)
        dhuhrView = findViewById(R.id.tv_dhuhr)
        asrView = findViewById(R.id.tv_asr)
        maghribView = findViewById(R.id.tv_maghrib)
        ishaView = findViewById(R.id.tv_isha)

        if (hasLocationPermission()) {
            Log.d("MainActivity", "Location permission already granted")
            fetchPrayerTimes()
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
            fetchPrayerTimes()
        } else {
            Log.d("MainActivity", "Location permission denied")
        }
    }

    private fun fetchPrayerTimes() {
        if (!hasLocationPermission()) return
        fusedClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                locationSender.sendLastLocation()
                updatePrayerTimes(loc)
            } else {
                Log.d("MainActivity", "No last location available")
            }
        }
    }

    private fun updatePrayerTimes(location: Location) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val method = CalculationMethod.valueOf(prefs.getString("calc_method", "TURKEY")!!)
        val madhab = Madhab.valueOf(prefs.getString("madhab", "SHAFI")!!)
        val params = method.parameters.copy(madhab = madhab)

        val date = LocalDate.now()
        val components = DateComponents(date.year, date.monthValue, date.dayOfMonth)
        val times = PrayerTimes(Coordinates(location.latitude, location.longitude), components, params)
        val zone = ZoneId.systemDefault()

        val fajr = times.fajr.toJavaInstant().atZone(zone).toLocalTime()
        val sunrise = times.sunrise.toJavaInstant().atZone(zone).toLocalTime()
        val dhuhr = times.dhuhr.toJavaInstant().atZone(zone).toLocalTime()
        val asr = times.asr.toJavaInstant().atZone(zone).toLocalTime()
        val maghrib = times.maghrib.toJavaInstant().atZone(zone).toLocalTime()
        val isha = times.isha.toJavaInstant().atZone(zone).toLocalTime()

        fajrView.text = getString(R.string.fajr_time, timeFormatter.format(fajr))
        sunriseView.text = getString(R.string.sunrise_time, timeFormatter.format(sunrise))
        dhuhrView.text = getString(R.string.dhuhr_time, timeFormatter.format(dhuhr))
        asrView.text = getString(R.string.asr_time, timeFormatter.format(asr))
        maghribView.text = getString(R.string.maghrib_time, timeFormatter.format(maghrib))
        ishaView.text = getString(R.string.isha_time, timeFormatter.format(isha))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }
}
