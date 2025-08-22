package com.noxob.namazvakti

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity(), CoroutineScope by MainScope() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        requestLocation()
    }

    private fun requestLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1001)
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                launch { resolveCityName(location) }
            }
        }
    }

    private suspend fun resolveCityName(location: Location) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val geocoder = Geocoder(this, Locale.getDefault())
            geocoder.getFromLocation(location.latitude, location.longitude, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<android.location.Address>) {
                    val city = addresses.firstOrNull()?.locality
                    Log.d("MainActivity", "City (geocoder): $city")
                }

                override fun onError(errorMessage: String?) {
                    Log.e("MainActivity", "Geocode error: $errorMessage")
                }
            })
        } else {
            val city = fetchCityFromApi(location.latitude, location.longitude)
            Log.d("MainActivity", "City (api): $city")
        }
    }

    private suspend fun fetchCityFromApi(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        val url = URL("https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon&zoom=10&addressdetails=1")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "NamazVaktiApp")
        try {
            conn.inputStream.use { stream ->
                val response = stream.bufferedReader().use { it.readText() }
                val address = JSONObject(response).optJSONObject("address") ?: return@withContext null
                val city = address.optString("city").takeIf { it.isNotEmpty() }
                    ?: address.optString("town").takeIf { it.isNotEmpty() }
                city
            }
        } finally {
            conn.disconnect()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestLocation()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel()
    }
}

