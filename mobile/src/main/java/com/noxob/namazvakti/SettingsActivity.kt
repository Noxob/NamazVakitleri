package com.noxob.namazvakti

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import com.batoulapps.adhan2.CalculationMethod

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val spinner = findViewById<Spinner>(R.id.method_spinner)
        val methods = CalculationMethod.entries.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, methods)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val current = prefs.getString("method", CalculationMethod.MUSLIM_WORLD_LEAGUE.name)
        spinner.setSelection(methods.indexOf(current))

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                prefs.edit().putString("method", methods[position]).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }
}
