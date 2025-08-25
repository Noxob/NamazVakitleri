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
import androidx.preference.PreferenceManager
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
import com.batoulapps.adhan2.Coordinates
import com.batoulapps.adhan2.Madhab
import com.batoulapps.adhan2.PrayerTimes as AdhanPrayerTimes
import com.batoulapps.adhan2.data.DateComponents
import kotlinx.datetime.toJavaInstant
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val locationSender by lazy { LocationSender(this) }
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

    // UI ilk açılırken boş kalmasın
    private var cityName: String = "Konum alınıyor…"
    private var lastLat: Double? = null
    private var lastLon: Double? = null

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

        populatePrayerUI(
            PrayerTimes(
                fajr = LocalTime.of(5, 0),
                sunrise = LocalTime.of(6, 30),
                dhuhr = LocalTime.of(13, 0),
                asr = LocalTime.of(17, 0),
                maghrib = LocalTime.of(20, 30),
                isha = LocalTime.of(22, 0)
            )
        )
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
        val lat = lastLat
        val lon = lastLon
        if (lat != null && lon != null) {
            updatePrayerTimes(lat, lon)
        }
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
                    lastLat = loc.latitude
                    lastLon = loc.longitude
                    reverseGeocodeAndSetCity(loc.latitude, loc.longitude)
                    updatePrayerTimes(loc.latitude, loc.longitude)
                } else {
                    fusedClient.lastLocation.addOnSuccessListener { last ->
                        if (last != null) {
                            lastLat = last.latitude
                            lastLon = last.longitude
                            reverseGeocodeAndSetCity(last.latitude, last.longitude)
                            updatePrayerTimes(last.latitude, last.longitude)
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

    private fun updatePrayerTimes(lat: Double, lon: Double) {
        lastLat = lat
        lastLon = lon
        lifecycleScope.launch(Dispatchers.Default) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
            val method = CalculationMethod.valueOf(
                prefs.getString("calculation_method", CalculationMethod.TURKEY.name)!!
            )
            val madhab = Madhab.valueOf(
                prefs.getString("madhab", Madhab.SHAFI.name)!!
            )
            val params = method.parameters.copy(madhab = madhab)
            val date = LocalDate.now()
            val components = DateComponents(date.year, date.monthValue, date.dayOfMonth)
            val times = AdhanPrayerTimes(Coordinates(lat, lon), components, params)
            val offsetMinutes = TimeZone.getDefault().rawOffset / 60000
            val zoneOffset = ZoneOffset.ofTotalSeconds(offsetMinutes * 60)
            val pt = PrayerTimes(
                fajr = times.fajr.toJavaInstant().atOffset(zoneOffset).toLocalTime(),
                sunrise = times.sunrise.toJavaInstant().atOffset(zoneOffset).toLocalTime(),
                dhuhr = times.dhuhr.toJavaInstant().atOffset(zoneOffset).toLocalTime(),
                asr = times.asr.toJavaInstant().atOffset(zoneOffset).toLocalTime(),
                maghrib = times.maghrib.toJavaInstant().atOffset(zoneOffset).toLocalTime(),
                isha = times.isha.toJavaInstant().atOffset(zoneOffset).toLocalTime()
            )
            withContext(Dispatchers.Main) {
                populatePrayerUI(pt)
            }
        }
    }

    private fun populatePrayerUI(prayerTimes: PrayerTimes) {
        val now = LocalTime.now()
        val (nextName, nextTime) = nextPrayer(now, prayerTimes)
        val countdown = Duration.between(now, nextTime)

        // Şehir adı başlangıçta placeholder; konum çözülünce updateCityFromLocation() günceller
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
