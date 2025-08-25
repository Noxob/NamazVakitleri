package com.noxob.namazvakti

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.card.MaterialCardView
import com.batoulapps.adhan2.CalculationMethod
import com.batoulapps.adhan2.CalculationParameters
import com.batoulapps.adhan2.Coordinates
import com.batoulapps.adhan2.PrayerTimes as AdhanPrayerTimes
import com.batoulapps.adhan2.data.DateComponents
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaInstant

class MainActivity : AppCompatActivity() {

    private val locationSender by lazy { LocationSender(this) }
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

    // UI ilk açılırken boş kalmasın
    private var cityName: String = "Konum alınıyor…"
    private var currentLat: Double? = null
    private var currentLon: Double? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<ImageButton>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        if (hasLocationPermission()) {
            Log.d("MainActivity", "Location permission already granted")
            locationSender.sendLastLocation()
            updateCityFromLocation()
        } else {
            Log.d("MainActivity", "Requesting location permission")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                0
            )
        }

        populatePrayerUI()
    }

    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d("MainActivity", "Location permission granted")
            locationSender.sendLastLocation()
            updateCityFromLocation()
        } else {
            Log.d("MainActivity", "Location permission denied")
            findViewById<TextView>(R.id.city_text).text = "Konum izni reddedildi"
        }
    }

    override fun onResume() {
        super.onResume()
        populatePrayerUI(currentLat, currentLon)
    }

    /** Konumu alır ve şehir adını UI'a yazar */
    private fun updateCityFromLocation() {
        if (!hasLocationPermission()) return

        val cts = CancellationTokenSource()
        // Önce hızlı, düşük maliyetli currentLocation; gelmezse lastLocation'a düş
        fusedClient
            .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    reverseGeocodeAndSetCity(loc.latitude, loc.longitude)
                } else {
                    fusedClient.lastLocation.addOnSuccessListener { last ->
                        if (last != null) {
                            reverseGeocodeAndSetCity(last.latitude, last.longitude)
                        } else {
                            findViewById<TextView>(R.id.city_text).text = "Konum alınamadı"
                        }
                    }.addOnFailureListener {
                        findViewById<TextView>(R.id.city_text).text = "Konum hatası"
                    }
                }
            }
            .addOnFailureListener {
                findViewById<TextView>(R.id.city_text).text = "Konum hatası"
            }
    }

    private fun setCityOnUi(text: String) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            cityName = text
            findViewById<TextView>(R.id.city_text).text = text
        }
    }

    /** Ters geocode: Koordinatlardan şehir adını çözer ve UI'ı günceller */
    private fun reverseGeocodeAndSetCity(lat: Double, lon: Double) {
        currentLat = lat
        currentLon = lon
        populatePrayerUI(lat, lon)

        val geocoder = Geocoder(this, Locale("tr", "TR"))

        fun pickName(addr: Address?): String {
            return listOfNotNull(
                addr?.locality,       // ilçe/şehir gelebilir
                addr?.subAdminArea,   // ilçe
                addr?.adminArea       // il
            ).firstOrNull() ?: "Bilinmeyen Konum"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(lat, lon, 1,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        setCityOnUi(pickName(addresses.firstOrNull()))
                    }
                    override fun onError(errorMessage: String?) {
                        Log.w("MainActivity", "Geocoder error: $errorMessage")
                        setCityOnUi("Konum çözülemedi")
                    }
                }
            )
        } else {
            lifecycleScope.launch {
                val addresses = withContext(Dispatchers.IO) {
                    try { @Suppress("DEPRECATION")
                    geocoder.getFromLocation(lat, lon, 1)
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Geocoder error", e)
                        null
                    }
                }
                setCityOnUi(pickName(addresses?.firstOrNull()))
            }
        }
    }


    private fun populatePrayerUI(lat: Double? = null, lon: Double? = null) {
        val prayerTimes = if (lat != null && lon != null) {
            val coords = Coordinates(lat, lon)
            val today = LocalDate.now()
            val params = getCalculationParameters()
            val adhanTimes = AdhanPrayerTimes(
                coords,
                DateComponents(today.year, today.monthValue, today.dayOfMonth),
                params
            )
            val zone = ZoneId.systemDefault()
            PrayerTimesDisplay(
                fajr = adhanTimes.fajr.toJavaInstant().atZone(zone).toLocalTime(),
                sunrise = adhanTimes.sunrise.toJavaInstant().atZone(zone).toLocalTime(),
                dhuhr = adhanTimes.dhuhr.toJavaInstant().atZone(zone).toLocalTime(),
                asr = adhanTimes.asr.toJavaInstant().atZone(zone).toLocalTime(),
                maghrib = adhanTimes.maghrib.toJavaInstant().atZone(zone).toLocalTime(),
                isha = adhanTimes.isha.toJavaInstant().atZone(zone).toLocalTime()
            )
        } else {
            PrayerTimesDisplay(
                fajr = LocalTime.of(5, 0),
                sunrise = LocalTime.of(6, 30),
                dhuhr = LocalTime.of(13, 0),
                asr = LocalTime.of(17, 0),
                maghrib = LocalTime.of(20, 30),
                isha = LocalTime.of(22, 0)
            )
        }

        val now = LocalTime.now()
        val (nextName, nextTime) = nextPrayer(now, prayerTimes)
        val countdown = Duration.between(now, nextTime)

        findViewById<TextView>(R.id.city_text).text = cityName
        findViewById<TextView>(R.id.next_prayer_label).text = "$nextName - ${formatTime(nextTime)}"
        findViewById<TextView>(R.id.next_prayer_countdown).text = formatDuration(countdown)

        val list = findViewById<LinearLayout>(R.id.prayer_list)
        list.removeAllViews()
        prayerTimes.asList().forEach { (name, time) ->
            val card = layoutInflater.inflate(R.layout.item_prayer_time, list, false) as MaterialCardView
            card.findViewById<ImageView>(R.id.icon).setImageResource(iconForPrayer(name))
            card.findViewById<TextView>(R.id.prayer_name).text = name
            card.findViewById<TextView>(R.id.prayer_time).text = formatTime(time)
            list.addView(card)
        }

        val kerahatText = if (isKerahat(now, prayerTimes)) "Kerahat vaktinde" else "Kerahat vakti değil"
        findViewById<TextView>(R.id.kerahat_status).text = kerahatText
    }

    private fun getCalculationParameters(): CalculationParameters {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val methodName = prefs.getString("method", CalculationMethod.MUSLIM_WORLD_LEAGUE.name)!!
        return CalculationMethod.valueOf(methodName).parameters
    }

    private fun iconForPrayer(name: String): Int = when (name) {
        "Fajr" -> R.drawable.ic_sunrise
        "Sunrise" -> R.drawable.ic_dhuhr
        "Dhuhr" -> R.drawable.ic_asr
        "Asr" -> R.drawable.ic_maghrib
        "Maghrib" -> R.drawable.ic_isha
        "Isha" -> R.drawable.ic_fajr
        else -> R.drawable.ic_sunrise
    }
}
