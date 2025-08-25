package com.noxob.namazvakti

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import com.batoulapps.adhan2.CalculationMethod
import com.batoulapps.adhan2.Madhab

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val methodSpinner = findViewById<Spinner>(R.id.method_spinner)
        val madhabSpinner = findViewById<Spinner>(R.id.madhab_spinner)

        val methodValues = CalculationMethod.entries.map { it.name }
        val madhabValues = Madhab.entries.map { it.name }

        methodSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            methodValues
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        madhabSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            madhabValues
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val savedMethod = prefs.getString("method", CalculationMethod.TURKEY.name)
        val savedMadhab = prefs.getString("madhab", Madhab.SHAFI.name)
        methodSpinner.setSelection(methodValues.indexOf(savedMethod))
        madhabSpinner.setSelection(madhabValues.indexOf(savedMadhab))

        findViewById<Button>(R.id.save_button).setOnClickListener {
            prefs.edit()
                .putString("method", methodSpinner.selectedItem as String)
                .putString("madhab", madhabSpinner.selectedItem as String)
                .apply()
            finish()
        }
    }
}
