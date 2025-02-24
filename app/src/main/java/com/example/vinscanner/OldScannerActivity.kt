package com.example.vinscanner

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.animation.AnimationUtils
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.vinscanner.ScannerActivity.Companion
import com.example.vinscanner.databinding.ActivityOldScannerBinding
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class OldScannerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityOldScannerBinding
    private lateinit var cameraExecutor: ExecutorService
    private val decodedVinList = ArrayList<String>(MAX_SCAN_COUNT)
    private var isScanning = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOldScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        }
    }

    private fun setupUI() {
        // Start scanner line animation
        val scanAnimation = AnimationUtils.loadAnimation(this, R.anim.scanner_line_animation)
        binding.scannerLine.startAnimation(scanAnimation)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindPreview(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindPreview(cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(binding.previewView.surfaceProvider)
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, BarcodeAndTextAnalyzer(
                    { barcode -> processBarcode(barcode) },
                    { text -> processOCRText(text) }
                ))
            }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis
            )
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun processOCRText(text: String) {
        if (!isScanning || decodedVinList.size >= MAX_SCAN_COUNT) return

        val potentialVINs = extractPotentialVINs(text)
        potentialVINs.forEach { vin ->
            if (isValidVin(vin)) {
                Log.d(TAG, "OCR Reading -> $vin")
                decodedVinList.add(vin)
                checkMaxVinArraySize()
            }
        }
    }
    private fun extractPotentialVINs(text: String): List<String> {
        val vinPattern = Regex("[A-HJ-NPR-Z0-9]{17}")
        return vinPattern.findAll(text).map { it.value }.toList()
    }
    private fun processBarcode(barcode: Barcode) {
        if (!isScanning || decodedVinList.size >= MAX_SCAN_COUNT) return

        val parsedCode = barcode.displayValue?.trimStart { it == 'I' || it == 'O' }
        if (parsedCode != null && isValidVin(parsedCode)) {
            decodedVinList.add(parsedCode)
            Log.d(TAG, "Barcode SCANNED : $parsedCode")
            checkMaxVinArraySize()
        }
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

    private class BarcodeAndTextAnalyzer(
        private val onBarcodeDetected: (Barcode) -> Unit,
        private val onTextDetected: (String) -> Unit
    ) : ImageAnalysis.Analyzer {
        private val barcodeScanner = BarcodeScanning.getClient()
        private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                processBarcodes(inputImage, imageProxy)
            } else {
                imageProxy.close()
            }
        }

        private fun processBarcodes(inputImage: InputImage, imageProxy: ImageProxy) {
            barcodeScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    barcodes.firstOrNull { isValidVin(it.displayValue) }?.let {
                        onBarcodeDetected(it)
                        imageProxy.close()
                        return@addOnSuccessListener // Skip OCR if barcode is detected
                    }
                    processText(inputImage, imageProxy) // Proceed to OCR if no barcode is found
                }
                .addOnFailureListener { imageProxy.close() }
        }

        private fun processText(inputImage: InputImage, imageProxy: ImageProxy) {
            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val potentialVINs = extractPotentialVINs(visionText.text)
                    potentialVINs.firstOrNull { isValidVin(it) }?.let {
                        onTextDetected(it)
                    }
                    imageProxy.close()
                }
                .addOnFailureListener { imageProxy.close() }
        }
        private fun extractPotentialVINs(text: String): List<String> {
            val vinPattern = Regex("[A-HJ-NPR-Z0-9]{17}")
            return vinPattern.findAll(text).map { it.value }.toList()
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
        this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE && allPermissionsGranted()) {
            startCamera()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "OldScannerActivity"
        private const val CAMERA_PERMISSION_REQUEST_CODE = 0
        private const val MAX_SCAN_COUNT = 10
        private val VIN_REGEX = "^(?!.*[IOQ])[A-HJ-NPR-Z0-9]{17}$".toRegex()

        private fun isValidVin(vin: String?): Boolean {
            if (vin == null || !vin.matches(VIN_REGEX)) return false

            val weights = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)
            val transliterations = mapOf(
                'A' to 1, 'B' to 2, 'C' to 3, 'D' to 4, 'E' to 5, 'F' to 6,
                'G' to 7, 'H' to 8, 'J' to 1, 'K' to 2, 'L' to 3, 'M' to 4,
                'N' to 5, 'P' to 7, 'R' to 9, 'S' to 2, 'T' to 3, 'U' to 4,
                'V' to 5, 'W' to 6, 'X' to 7, 'Y' to 8, 'Z' to 9
            )

            var sum = 0
            for (i in vin.indices) {
                val char = vin[i]
                val value = if (char.isDigit()) char.toString().toInt() else transliterations[char] ?: 0
                sum += value * weights[i]
            }

            val checksum = sum % 11
            val checksumChar = if (checksum == 10) 'X' else checksum.toString()[0]
            return vin[8] == checksumChar
        }
    }
}