package com.noxob.namazvakti

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

class LocationSender(private val context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val dataClient = Wearable.getDataClient(context)

    fun sendLastLocation() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        fusedClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val request = PutDataMapRequest.create("/location").apply {
                    dataMap.putDouble("lat", location.latitude)
                    dataMap.putDouble("lng", location.longitude)
                }.asPutDataRequest().setUrgent()
                dataClient.putDataItem(request)
            }
        }
    }
}
