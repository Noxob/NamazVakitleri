package com.noxob.namazvakti

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread
import org.json.JSONObject

class LocationSender(private val context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val dataClient = Wearable.getDataClient(context)

    fun sendLastLocation(onCity: (String) -> Unit) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.d("LocationSender", "Location permission not granted")
            return
        }
        fusedClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                send(location)
                resolveCity(location, onCity)
            } else {
                Log.d("LocationSender", "No last location available; requesting current location")
                fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                    .addOnSuccessListener { current ->
                        if (current != null) {
                            send(current)
                            resolveCity(current, onCity)
                        } else {
                            Log.d("LocationSender", "Current location unavailable")
                        }
                    }
            }
        }
    }

    private fun send(location: Location) {
        Log.d("LocationSender", "Sending location ${'$'}{location.latitude}, ${'$'}{location.longitude}")
        val request = PutDataMapRequest.create("/location").apply {
            dataMap.putDouble("lat", location.latitude)
            dataMap.putDouble("lng", location.longitude)
            // Include a timestamp so each update is propagated
            dataMap.putLong("time", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request)
    }

    private fun resolveCity(location: Location, onCity: (String) -> Unit) {
        val geocoder = Geocoder(context, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(location.latitude, location.longitude, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    val city = addresses.firstOrNull()?.locality ?: ""
                    onCity(city)
                }

                override fun onError(errorMessage: String?) {
                    Log.e("LocationSender", "Geocoder error: ${'$'}errorMessage")
                }
            })
        } else {
            thread {
                try {
                    val url = URL("https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${'$'}{location.latitude}&lon=${'$'}{location.longitude}&addressdetails=1")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "NamazVakitleri/1.0")
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val city = JSONObject(response).optJSONObject("address")?.optString("city").orEmpty()
                    onCity(city)
                } catch (e: Exception) {
                    Log.e("LocationSender", "Error fetching city", e)
                }
            }
        }
    }
}
