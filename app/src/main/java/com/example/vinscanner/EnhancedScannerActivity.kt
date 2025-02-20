package com.example.vinscanner

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.vinscanner.databinding.ActivityEnhancedScannerBinding
import me.dm7.barcodescanner.zbar.Result
import me.dm7.barcodescanner.zbar.ZBarScannerView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class EnhancedScannerActivity : AppCompatActivity(), ZBarScannerView.ResultHandler {
    private lateinit var binding: ActivityEnhancedScannerBinding
    private lateinit var cameraExecutor: ExecutorService
    private val decodedVinList = ArrayList<String>(MAX_SCAN_COUNT)
    private var isScanning = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEnhancedScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        initializeScanner()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        }
    }

    private fun setupUI() {
        val scanAnimation = AnimationUtils.loadAnimation(this, R.anim.scanner_line_animation)
        binding.scannerLine.startAnimation(scanAnimation)
    }

    private fun initializeScanner() {
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Configure ZBar scanner
        binding.zbarScanner.apply {
            setResultHandler(this@EnhancedScannerActivity)
            setAutoFocus(true)
            setFlash(false)
            setAspectTolerance(0.2f)
            setFormats(listOf(
                me.dm7.barcodescanner.zbar.BarcodeFormat.CODE128,
                me.dm7.barcodescanner.zbar.BarcodeFormat.CODE39,
                me.dm7.barcodescanner.zbar.BarcodeFormat.QRCODE,
                me.dm7.barcodescanner.zbar.BarcodeFormat.EAN13
            ))
        }
    }

    private fun startCamera() {
        binding.zbarScanner.startCamera()
    }

    override fun handleResult(result: Result?) {
        result?.let {
            val scannedText = it.contents
            if (isValidVin(scannedText)) {
                processVin(scannedText, it.barcodeFormat)
            }
            // Resume scanning after a short delay
            if (isScanning) {
                binding.zbarScanner.postDelayed({
                    binding.zbarScanner.resumeCameraPreview(this)
                }, 500)
            }
        }
    }

    private fun processVin(vin: String, format: me.dm7.barcodescanner.zbar.BarcodeFormat) {
        if (!isScanning || decodedVinList.size >= MAX_SCAN_COUNT) return

        val confidence = calculateConfidence(vin, format)
        if (confidence > MIN_CONFIDENCE) {
            decodedVinList.add(vin)
            Log.d(TAG, "VIN Scanned: $vin (Confidence: $confidence)")
            checkMaxVinArraySize()
        }
    }

    private fun calculateConfidence(vin: String, format: me.dm7.barcodescanner.zbar.BarcodeFormat): Float {
        var confidence = 1.0f

        // Format-based confidence
        confidence *= when (format) {
            me.dm7.barcodescanner.zbar.BarcodeFormat.CODE128 -> 1.0f
            me.dm7.barcodescanner.zbar.BarcodeFormat.CODE39 -> 0.9f
            me.dm7.barcodescanner.zbar.BarcodeFormat.QRCODE -> 0.95f
            else -> 0.8f
        }

        // Length check
        if (vin.length != 17) {
            confidence *= 0.7f
        }

        // Character check
        if (vin.contains('O') || vin.contains('I')) {
            confidence *= 0.8f
        }

        return confidence
    }

    private fun checkMaxVinArraySize() {
        if (decodedVinList.isNotEmpty() && decodedVinList.size >= MAX_SCAN_COUNT) {
            findMostCommonString(decodedVinList)?.let { maxValue ->
                Log.d(TAG, "MAX Scanned VIN : $maxValue")
                isScanning = false
                returnResult("VIN", maxValue)
            }
        }
    }

    private fun findMostCommonString(strings: ArrayList<String>): String? {
        return strings.groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
    }

    private fun returnResult(type: String, value: String) {
        val resultIntent = Intent().apply {
            putExtra("SCANNED_VALUE", value)
            putExtra("SCAN_TYPE", type)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()
        binding.zbarScanner.resumeCameraPreview(this)
    }

    override fun onPause() {
        super.onPause()
        binding.zbarScanner.stopCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "ZBarScannerActivity"
        private const val CAMERA_PERMISSION_REQUEST_CODE = 0
        private const val MAX_SCAN_COUNT = 15
        private const val MIN_CONFIDENCE = 0.7f
        private val VIN_REGEX = "^[A-HJ-NPR-Z0-9]{17}$".toRegex()

        private fun isValidVin(vin: String?): Boolean {
            return vin?.trimStart { it == 'I' || it == 'O' }?.matches(VIN_REGEX) == true
        }
    }
}