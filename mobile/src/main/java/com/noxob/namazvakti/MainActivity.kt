package com.noxob.namazvakti

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                render()
            } else {
                Log.d("MainActivity", "Location permission denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (hasLocationPermission()) {
            render()
        } else {
            requestPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun render() {
        setContent {
            var uiState by remember { mutableStateOf<PrayerUiState?>(null) }
            LaunchedEffect(Unit) {
                loadPrayerInfo { uiState = it }
            }
            if (uiState == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                PrayerTimesScreen(uiState!!)
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun loadPrayerInfo(onReady: (PrayerUiState) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val loc = fusedClient.lastLocation.await()
                val lat = loc.latitude
                val lng = loc.longitude
                val today = computeTimes(lat, lng, LocalDate.now())
                val tomorrow = computeTimes(lat, lng, LocalDate.now().plusDays(1))
                val yesterday = computeTimes(lat, lng, LocalDate.now().minusDays(1))
                val now = LocalDateTime.now()
                val (name, _, end) = prayerWindow(now, yesterday, today, tomorrow)
                val kerahat = kerahatInterval(now, today)
                val city = resolveCity(this@MainActivity, lat, lng)
                val times = listOf(
                    "Fajr" to today[0],
                    "Sunrise" to today[1],
                    "Dhuhr" to today[2],
                    "Asr" to today[3],
                    "Maghrib" to today[4],
                    "Isha" to today[5]
                )
                LocationSender(this@MainActivity).sendLastLocation()
                onReady(
                    PrayerUiState(
                        city = city,
                        times = times,
                        nextName = name,
                        nextTime = end,
                        kerahat = kerahat
                    )
                )
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading times", e)
            }
        }
    }
}

data class PrayerUiState(
    val city: String,
    val times: List<Pair<String, LocalTime>>,
    val nextName: String,
    val nextTime: LocalDateTime,
    val kerahat: Pair<LocalDateTime, LocalDateTime>?
)

@Composable
fun PrayerTimesScreen(state: PrayerUiState) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            now = LocalDateTime.now()
        }
    }
    val remaining = Duration.between(now, state.nextTime)
    val hours = remaining.toHours()
    val minutes = remaining.minusHours(hours).toMinutes()
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = state.city, style = MaterialTheme.typography.headlineSmall)
            if (state.kerahat != null) {
                Text(
                    text = "Kerahat vaktinde",
                    color = Color.Red,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = "Next ${state.nextName} in ${hours}h ${minutes}m",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            state.times.forEach { (name, time) ->
                Text("$name: ${time.format(timeFormatter)}")
            }
        }
    }
}
