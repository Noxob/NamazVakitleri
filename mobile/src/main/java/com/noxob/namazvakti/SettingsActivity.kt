package com.noxob.namazvakti

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import com.batoulapps.adhan2.CalculationMethod
import com.batoulapps.adhan2.Madhab

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)

        val methodSpinner = findViewById<Spinner>(R.id.method_spinner)
        val methods = CalculationMethod.values()
        methodSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            methods.map { it.name }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val currentMethod = prefs.getString("method", CalculationMethod.TURKEY.name)
        methodSpinner.setSelection(methods.indexOfFirst { it.name == currentMethod })
        methodSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                prefs.edit().putString("method", methods[position].name).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        val madhabSpinner = findViewById<Spinner>(R.id.madhab_spinner)
        val madhabs = Madhab.values()
        madhabSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            madhabs.map { it.name }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val currentMadhab = prefs.getString("madhab", Madhab.SHAFI.name)
        madhabSpinner.setSelection(madhabs.indexOfFirst { it.name == currentMadhab })
        madhabSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                prefs.edit().putString("madhab", madhabs[position].name).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }
}
