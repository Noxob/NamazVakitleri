package com.noxob.namazvakti.presentation

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.tooling.preview.devices.WearDevices
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.location.LocationServices
import com.noxob.namazvakti.R
import com.noxob.namazvakti.complication.MainComplicationService
import com.noxob.namazvakti.presentation.theme.NamazVaktiTheme
import com.noxob.namazvakti.*
import java.time.Duration
import java.time.LocalTime
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var city by mutableStateOf("-")

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)

        setTheme(android.R.style.Theme_DeviceDefault)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 0)
        } else {
            updateLocation()
        }

        // Request an update for the complication whenever the app is opened to simplify debugging
        ComplicationDataSourceUpdateRequester.create(
            this,
            ComponentName(this, MainComplicationService::class.java)
        ).requestUpdateAll().also {
            Log.d("MainActivity", "Requested complication update")
        }

        setContent {
            WearApp(city)
        }
    }

    private fun updateLocation() {
        fusedClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                try {
                    val geocoder = Geocoder(this, Locale.getDefault())
                    val name = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                        ?.firstOrNull()?.locality
                    if (name != null) city = name
                } catch (e: Exception) {
                    Log.e("MainActivity", "Geocoder failed", e)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            updateLocation()
        }
    }
}

@Composable
fun WearApp(city: String) {
    NamazVaktiTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            contentAlignment = Alignment.Center
        ) {
            TimeText()
            PrayerContent(city)
        }
    }
}

@Composable
fun PrayerContent(city: String) {
    val prayerTimes = samplePrayerTimes()
    val now = LocalTime.now()
    val (nextName, nextTime) = nextPrayer(now, prayerTimes)
    val countdown = Duration.between(now, nextTime)
    val others = prayerTimes.asList().joinToString("\n") { "${it.first}: ${formatTime(it.second)}" }
    val kerahat = if (isKerahat(now, prayerTimes)) "Kerahat" else "Normal"

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(city, textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2)
        Text("$nextName ${formatTime(nextTime)}", textAlign = TextAlign.Center, style = MaterialTheme.typography.title3)
        Text(formatDuration(countdown), textAlign = TextAlign.Center, style = MaterialTheme.typography.caption1)
        Text(others, textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2)
        Text(kerahat, textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2)
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun DefaultPreview() {
    WearApp("İstanbul")
}
