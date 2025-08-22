package com.noxob.namazvakti

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.util.Locale

class LocationSender(private val context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val dataClient = Wearable.getDataClient(context)

    fun sendLastLocation(onCity: ((String) -> Unit)? = null) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.d("LocationSender", "Location permission not granted")
            return
        }
        fusedClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                send(location, onCity)
            } else {
                Log.d("LocationSender", "No last location available; requesting current location")
                fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                    .addOnSuccessListener { current ->
                        if (current != null) {
                            send(current, onCity)
                        } else {
                            Log.d("LocationSender", "Current location unavailable")
                        }
                    }
            }
        }
    }

    private fun send(location: Location, onCity: ((String) -> Unit)?) {
        Log.d("LocationSender", "Sending location ${'$'}{location.latitude}, ${'$'}{location.longitude}")
        val request = PutDataMapRequest.create("/location").apply {
            dataMap.putDouble("lat", location.latitude)
            dataMap.putDouble("lng", location.longitude)
            // Include a timestamp so each update is propagated
            dataMap.putLong("time", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request)
        onCity?.let { callback ->
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                val city = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()?.locality
                if (city != null) callback(city)
            } catch (e: Exception) {
                Log.e("LocationSender", "Geocoder failed", e)
            }
        }
    }
}
