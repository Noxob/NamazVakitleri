package com.noxob.namazvakti

import android.content.Context
import androidx.preference.PreferenceManager
import com.batoulapps.adhan2.CalculationMethod
import com.batoulapps.adhan2.Madhab
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

class SettingsSender(private val context: Context) {
    private val dataClient = Wearable.getDataClient(context)

    fun send() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val method = prefs.getString(
            "calc_method",
            CalculationMethod.MUSLIM_WORLD_LEAGUE.name
        )!!
        val madhab = prefs.getString(
            "madhab",
            Madhab.SHAFI.name
        )!!
        val language = prefs.getString("language", "tr")!!
        val request = PutDataMapRequest.create("/settings").apply {
            dataMap.putString("calc_method", method)
            dataMap.putString("madhab", madhab)
            dataMap.putString("language", language)
            dataMap.putLong("time", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request)
    }
}
