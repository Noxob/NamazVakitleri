package com.noxob.namazvakti.data

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import java.time.LocalTime

class PrayerTimesRepository(private val context: Context) {
    suspend fun getPrayerTimes(): List<LocalTime>? {
        val uri = Uri.parse("wear://*/prayer_times")
        val item = Wearable.getDataClient(context).getDataItem(uri).await() ?: return null
        val dataMap = DataMapItem.fromDataItem(item).dataMap
        val times = dataMap.getStringArrayList("times") ?: return null
        return times.map { LocalTime.parse(it) }
    }
}
