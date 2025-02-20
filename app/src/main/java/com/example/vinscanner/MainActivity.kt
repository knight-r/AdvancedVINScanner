package com.example.vinscanner

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.vinscanner.databinding.ActivityMainBinding


class MainActivity : AppCompatActivity() {


    private lateinit var binding: ActivityMainBinding
    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scannedValue = result.data?.getStringExtra("SCANNED_VALUE")
            val scanType = result.data?.getStringExtra("SCAN_TYPE")
            binding.resultText.text = "Scanned $scanType: $scannedValue"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOpenScanner.setOnClickListener {
            scannerLauncher.launch(Intent(this, ScannerActivity::class.java))
        }

        binding.btnOldScanner.setOnClickListener {
            scannerLauncher.launch(Intent(this, OldScannerActivity::class.java))
        }

        binding.btnEnhancedScanner.setOnClickListener {
            scannerLauncher.launch(Intent(this, EnhancedScannerActivity::class.java))
        }
    }
}