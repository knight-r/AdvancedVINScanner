package com.example.vinscanner

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.animation.AnimationUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.vinscanner.databinding.ActivityScannerBinding
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern

class ScannerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityScannerBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner
    private lateinit var textRecognizer: com.google.mlkit.vision.text.TextRecognizer
    private var isScanning = true
    private val decodedVinList = ArrayList<String>(MAX_SCAN_COUNT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializeScanners()
        setupUI()

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

    private fun initializeScanners() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetRotation(binding.previewView.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    if (isScanning) {
                        processImage(imageProxy)
                    } else {
                        imageProxy.close()
                    }
                }
            }

        try {
            cameraProvider.unbindAll()
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()
            cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalysis
            )
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed", e)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            // Process barcode first
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty()) {
                        barcodes.firstOrNull { isValidVin(it.displayValue) }?.let {
                            processBarcode(it)
                        }
                    }
                    // If no valid barcode found, try OCR
                    if (isScanning) {
                        processTextRecognition(image)
                    }
                }
                .addOnFailureListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun processTextRecognition(image: InputImage) {
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                if (isScanning) {
                    val horizontalText = extractHorizontalText(visionText)
                    processOCRText(horizontalText)
                }
            }
            .addOnFailureListener {
                Log.e(TAG, "Text recognition failed", it)
            }
    }

    private fun extractHorizontalText(visionText: Text): String {
        val horizontalTextBuilder = StringBuilder()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                if (isLineHorizontal(line)) {
                    horizontalTextBuilder.append(line.text).append("\n")
                }
            }
        }
        return horizontalTextBuilder.toString()
    }

    private fun isLineHorizontal(line: Text.Line): Boolean {
        val cornerPoints = line.cornerPoints ?: return false
        val topLeft = cornerPoints[0]
        val bottomLeft = cornerPoints[3]
        val angle = Math.toDegrees(
            kotlin.math.atan2(
                (bottomLeft.y - topLeft.y).toDouble(),
                (bottomLeft.x - topLeft.x).toDouble()
            )
        )
        return angle in -10.0..10.0
    }

    private fun processOCRText(text: String) {
        if (decodedVinList.size >= MAX_SCAN_COUNT) return
        if (text.isNotEmpty()) {
            val textArray = text.split("\n")
            val vinTextArray = textArray.filter { it.contains("VIN") }.takeIf { it.isNotEmpty() } ?: textArray
            vinTextArray.forEach { data ->
                val scannedVinText = data.replace("VIN","")
                    .replace(":","")
                    .replace("-","")
                    .replace("VIN:","")
                    .replace(" ","")
                    .trim()
                if (isValidVin(scannedVinText)) {
                    Log.d(TAG, "OCR Reading -> $scannedVinText")
                    decodedVinList.add(scannedVinText)
                    checkMaxVinArraySize()
                }
            }
        }
    }

    private fun processBarcode(barcode: Barcode) {
        if (decodedVinList.size >= MAX_SCAN_COUNT) return
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
        isScanning = false
        val resultIntent = Intent().apply {
            putExtra("SCANNED_VALUE", value)
            putExtra("SCAN_TYPE", type)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

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
        private const val TAG = "ScannerActivity"
        private const val CAMERA_PERMISSION_REQUEST_CODE = 0
        private const val MAX_SCAN_COUNT = 15
        private val VIN_REGEX = "^[A-HJ-NPR-Z0-9]{17}$".toRegex()

        private fun isValidVin(vin: String?): Boolean {
            return vin?.trimStart { it == 'I' || it == 'O' }?.matches(VIN_REGEX) == true
        }
    }
}