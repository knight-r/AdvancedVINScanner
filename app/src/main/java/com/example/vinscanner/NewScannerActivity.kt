package com.example.vinscanner

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.animation.AnimationUtils
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.vinscanner.databinding.ActivityOldScannerBinding
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NewScannerActivity : AppCompatActivity() {
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
        // Optimize preview with higher resolution for better barcode detection
        val preview = Preview.Builder()
            .build()
            .apply {
                setSurfaceProvider(binding.previewView.surfaceProvider)
            }

        // Optimize image analysis for barcode scanning
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        // Create analyzer with optimized options
        val analyzer = OptimizedBarcodeAndTextAnalyzer(
            onVinDetected = { result -> processVinResult(result) }
        )

        imageAnalysis.setAnalyzer(cameraExecutor, analyzer)

        try {
            cameraProvider.unbindAll()
            val camera = cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis
            )

            // Set camera control for dynamic adjustments
            analyzer.setCameraControl(camera.cameraControl)

            // Optimize focus for barcode scanning
            setupOptimizedFocus(camera)

        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun setupOptimizedFocus(camera: Camera) {
        // Create a focus point factory
        val factory = SurfaceOrientedMeteringPointFactory(
            binding.previewView.width.toFloat(),
            binding.previewView.height.toFloat()
        )

        // Create multiple focus points for better barcode detection
        val centerPoint = factory.createPoint(0.5f, 0.5f)

        // Create action with multiple focus points
        val action = FocusMeteringAction.Builder(centerPoint, FocusMeteringAction.FLAG_AF)
            .addPoint(factory.createPoint(0.5f, 0.3f), FocusMeteringAction.FLAG_AF) // Upper middle
            .addPoint(factory.createPoint(0.5f, 0.7f), FocusMeteringAction.FLAG_AF) // Lower middle
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()

        camera.cameraControl.startFocusAndMetering(action)
    }


    private class OptimizedBarcodeAndTextAnalyzer(
        private val onVinDetected: (VinScanResult) -> Unit
    ) : ImageAnalysis.Analyzer {
        // Create optimized barcode scanner options
        private val barcodeScannerOptions = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_PDF417
            )
            .build()

        private val barcodeScanner = BarcodeScanning.getClient(barcodeScannerOptions)
        private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        private var lastProcessTime = 0L
        private val processedVins = mutableSetOf<String>()
        private var consecutiveFailures = 0
        private var currentZoomRatio = 1.0f
        private var cameraControl: CameraControl? = null

        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            val currentTime = System.currentTimeMillis()

            // Rate limiting to avoid excessive processing
            if (currentTime - lastProcessTime < PROCESS_INTERVAL_MS) {
                imageProxy.close()
                return
            }
            lastProcessTime = currentTime

            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                processOptimizedBarcodes(inputImage, imageProxy)
            } else {
                imageProxy.close()
            }
        }

        private fun processOptimizedBarcodes(inputImage: InputImage, imageProxy: ImageProxy) {
            // Apply dynamic zoom adjustment based on previous results
            adjustZoomIfNeeded()

            barcodeScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    // Reset failure counter on success
                    consecutiveFailures = 0

                    val validBarcodes = barcodes
                        .mapNotNull { barcode ->
                            val rawValue = barcode.rawValue
                            val displayValue = barcode.displayValue

                            // Try both raw and display values for better accuracy
                            val value = when {
                                isLikelyVin(rawValue) -> processVinCandidate(rawValue)
                                isLikelyVin(displayValue) -> processVinCandidate(displayValue)
                                else -> null
                            }

                            if (value != null) {
                                Log.d("BarcodeScanner", "Potential VIN: $value (format: ${barcode.format})")
                                value to calculateBarcodeConfidence(barcode)
                            } else null
                        }
                        .sortedByDescending { it.second } // Sort by confidence

                    if (validBarcodes.isNotEmpty()) {
                        val (bestVin, confidence) = validBarcodes.first()
                        onVinDetected(VinScanResult(
                            bestVin,
                            confidence,
                            ScanSource.BARCODE,
                            System.currentTimeMillis()
                        ))
                        imageProxy.close()
                    } else {
                        // Only try OCR if no valid barcode found
                        processText(inputImage, imageProxy)
                    }
                }
                .addOnFailureListener {
                    // Track consecutive failures for adaptive strategies
                    consecutiveFailures++
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
                .addOnFailureListener { imageProxy.close() }
        }

        private fun isLikelyVin(text: String?): Boolean {
            if (text == null) return false

            // Quick pre-check before more expensive validation
            return text.length >= 17 &&
                    text.length <= 18 && // Allow for one potential extra character
                    text.matches(Regex(".*[A-HJ-NPR-Z0-9]{17}.*")) // Contains 17 VIN characters
        }

        private fun processVinCandidate(text: String?): String? {
            if (text == null) return null

            // Apply advanced VIN string processing
            val processed = text
                .replace('O', '0')
                .replace('I', '1')
                .replace('o', '0')
                .replace('i', '1')
                .replace('Q', '0')
                .replace('q', '0')
                .replace(" ", "") // Remove any spaces
                .trim()

            // Extract 17-character sequences that could be VINs
            val vinPattern = Regex("[A-HJ-NPR-Z0-9]{17}")
            val matches = vinPattern.findAll(processed)

            // Find first valid VIN in the text
            matches.forEach { match ->
                val potentialVin = match.value
                if (isValidVin(potentialVin) && !processedVins.contains(potentialVin)) {
                    processedVins.add(potentialVin)
                    return potentialVin
                }
            }

            return null
        }

        private fun calculateBarcodeConfidence(barcode: Barcode): Float {
            // Enhanced confidence calculation for barcodes
            val baseConfidence = when (barcode.format) {
                Barcode.FORMAT_CODE_128 -> 0.95f
                Barcode.FORMAT_CODE_39 -> 0.90f
                Barcode.FORMAT_DATA_MATRIX -> 0.85f
                Barcode.FORMAT_QR_CODE -> 0.95f
                Barcode.FORMAT_PDF417 -> 0.90f
                else -> 0.80f
            }

            // Adjust confidence based on barcode properties
            val boundingBox = barcode.boundingBox
            val sizeMultiplier = if (boundingBox != null) {
                val width = boundingBox.width()
                val height = boundingBox.height()

                // Size-based confidence adjustment
                when {
                    width > 200 && height > 50 -> 1.1f  // Large, clear barcode
                    width < 100 || height < 25 -> 0.9f  // Small barcode, might be less reliable
                    else -> 1.0f  // Standard size
                }
            } else 1.0f

            return (baseConfidence * sizeMultiplier).coerceAtMost(1.0f)
        }

        private fun adjustZoomIfNeeded() {
            // Dynamic zoom strategy - adjust zoom based on success/failure rate
            if (cameraControl == null) return

            when {
                consecutiveFailures > 5 -> {
                    // After several failures, try changing zoom to find barcodes
                    currentZoomRatio = if (currentZoomRatio > 1.5f) 1.0f else currentZoomRatio + 0.2f
                    cameraControl?.setZoomRatio(currentZoomRatio)
                    Log.d("BarcodeScanner", "Adjusting zoom to $currentZoomRatio after consecutive failures")
                }
            }
        }

        private fun cleanVinString(input: String): String {
            return input
                .replace('O', '0')
                .replace('I', '1')
                .replace('o', '0')
                .replace('i', '1')
                .replace('Q', '0')
                .replace('q', '0')
                .replace(" ", "")
                .trim()
        }

        fun setCameraControl(control: CameraControl) {
            this.cameraControl = control
        }

        companion object {
            private const val PROCESS_INTERVAL_MS = 150L // Faster processing for barcodes
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