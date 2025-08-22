package com.noxob.namazvakti

import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.time.LocalDate
import java.time.ZoneOffset

object PrayerTimesSync {
    suspend fun update(context: Context) {
        val locationClient = LocationServices.getFusedLocationProviderClient(context)
        val location = locationClient.lastLocation.await() ?: return
        val date = LocalDate.now()
        val offsetMin = ZoneOffset.systemDefault().rules.getOffset(date.atStartOfDay()).totalSeconds / 60
        val url = "https://vakit.vercel.app/api/timesForGPS?lat=${'$'}{location.latitude}&lng=${'$'}{location.longitude}&date=${'$'}date&days=1&timezoneOffset=${'$'}offsetMin&calculationMethod=Turkey&lang=tr"
        val json = withContext(Dispatchers.IO) {
            URL(url).openStream().bufferedReader().use { it.readText() }
        }
        val timesArray = JSONObject(json).getJSONObject("times").getJSONArray(date.toString())
        val times = ArrayList<String>()
        for (i in 0 until timesArray.length()) {
            times.add(timesArray.getString(i))
        }
        val request = PutDataMapRequest.create("/prayer_times").apply {
            dataMap.putStringArrayList("times", times)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request).await()
    }
}
