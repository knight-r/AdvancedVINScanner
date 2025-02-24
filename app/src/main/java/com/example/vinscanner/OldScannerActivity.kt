package com.example.vinscanner

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.animation.AnimationUtils
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
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
    private val decodedVinList = ArrayList<VinScanResult>(MAX_SCAN_COUNT)
    private var isScanning = true
    private var lastProcessedTimestamp = 0L

    data class VinScanResult(
        val value: String,
        val confidence: Float,
        val source: ScanSource,
        val timestamp: Long
    )

    enum class ScanSource {
        BARCODE,
        OCR
    }

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
        val preview = Preview.Builder()
            .build()
            .apply {
                setSurfaceProvider(binding.previewView.surfaceProvider)
            }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, EnhancedBarcodeAndTextAnalyzer(
                    onVinDetected = { result -> processVinResult(result) }
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

    private fun setupAutoFocus(camera: Camera) {
        val factory = SurfaceOrientedMeteringPointFactory(
            binding.previewView.width.toFloat(),
            binding.previewView.height.toFloat()
        )
        val centerPoint = factory.createPoint(0.5f, 0.5f)

        val action = FocusMeteringAction.Builder(centerPoint, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(2, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        camera.cameraControl.startFocusAndMetering(action)
    }

    private class EnhancedBarcodeAndTextAnalyzer(
        private val onVinDetected: (VinScanResult) -> Unit
    ) : ImageAnalysis.Analyzer {
        private val barcodeScanner = BarcodeScanning.getClient()
        private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        private var lastProcessTime = 0L
        private val processedVins = mutableSetOf<String>()

        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastProcessTime < PROCESS_INTERVAL_MS) {
                imageProxy.close()
                return
            }
            lastProcessTime = currentTime

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
                    var foundValidBarcode = false

                    for (barcode in barcodes) {
                        val cleanedValue = barcode.displayValue?.let { cleanVinString(it) }
                        if (cleanedValue != null && isValidVin(cleanedValue)) {
                            Log.d("Scanner", "Found barcode: $cleanedValue")
                            onVinDetected(VinScanResult(
                                cleanedValue,
                                0.9f,
                                ScanSource.BARCODE,
                                System.currentTimeMillis()
                            ))
                            foundValidBarcode = true
                            break
                        }
                    }

                    if (!foundValidBarcode) {
                        // Only try OCR if no valid barcode found
                        processText(inputImage, imageProxy)
                    } else {
                        imageProxy.close()
                    }
                }
                .addOnFailureListener {
                    processText(inputImage, imageProxy)
                }
        }

        private fun processText(inputImage: InputImage, imageProxy: ImageProxy) {
            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val text = visionText.text
                    Log.d("Scanner", "OCR Text: $text")

                    val vinPattern = Regex("[A-HJ-NPR-Z0-9]{17}")
                    val matches = vinPattern.findAll(text)

                    matches.forEach { match ->
                        val potentialVin = cleanVinString(match.value)
                        if (isValidVin(potentialVin)) {
                            Log.d("Scanner", "Found VIN in OCR: $potentialVin")
                            onVinDetected(VinScanResult(
                                potentialVin,
                                0.8f,
                                ScanSource.OCR,
                                System.currentTimeMillis()
                            ))
                        }
                    }
                    imageProxy.close()
                }
                .addOnFailureListener {
                    imageProxy.close()
                }
        }

        private fun cleanVinString(input: String): String {
            return input
                .replace('O', '0')
                .replace('I', '1')
                .replace('Q', '0')
                .trim()
        }
        companion object {
            private const val PROCESS_INTERVAL_MS = 200L
            private const val MIN_BARCODE_WIDTH = 100
            private const val MIN_BRIGHTNESS = 40f
        }
    }

    private fun processVinResult(result: VinScanResult) {
        if (!isScanning || decodedVinList.size >= MAX_SCAN_COUNT) return

        decodedVinList.add(result)
        Log.d(TAG, "Processed VIN: ${result.value} (${result.source}, confidence: ${result.confidence})")
        checkMaxVinArraySize()
    }

    private fun checkMaxVinArraySize() {
        if (decodedVinList.size >= MAX_SCAN_COUNT) {
            findMostReliableVin()?.let { maxValue ->
                Log.d(TAG, "Final VIN selected: $maxValue")
                isScanning = false
                returnResult("VIN", maxValue)
            }
        }
    }

    private fun findMostReliableVin(): String? {
        return decodedVinList
            .groupBy { it.value }
            .maxByOrNull { (_, results) ->
                // Weight by both frequency and confidence
                results.size * results.map { it.confidence }.average()
            }
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
        private const val MAX_SCAN_COUNT = 1
        private const val MIN_PROCESS_INTERVAL = 200L
        private val VIN_REGEX = "^(?!.*[IOQ])[A-HJ-NPR-Z0-9]{17}$".toRegex()

        // Existing VIN validation logic remains the same
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