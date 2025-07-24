// MainActivity.kt
package com.example.publictransportticketapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.widget.*
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity() {

    private lateinit var scannedInfoTextView: TextView

    // QR Code scanner launcher
    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents == null) {
            Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show()
        } else {
            // Display scanned QR code content
            displayScannedInfo(result.contents)
            Toast.makeText(this, "Scanned: ${result.contents}", Toast.LENGTH_SHORT).show()
        }
    }

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startQRCodeScanner()
        } else {
            Toast.makeText(this, "Camera permission required to scan QR codes", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupUI()
    }

    private fun setupUI() {
        // Set up click listeners
        val purchaseButton = findViewById<Button>(R.id.purchaseButton)
        val readTicketButton = findViewById<LinearLayout>(R.id.readTicketButton)

        // Initialize TextView for displaying scanned info
        scannedInfoTextView = findViewById<TextView>(R.id.scannedInfoTextView)

        purchaseButton.setOnClickListener {
            // Handle purchase logic
            Toast.makeText(this, "Purchase", Toast.LENGTH_SHORT).show()
        }

        readTicketButton.setOnClickListener {
            // Check camera permission and start QR scanner
            checkCameraPermissionAndScan()
        }
    }

    private fun checkCameraPermissionAndScan() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted, start QR scanner
                startQRCodeScanner()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                // Show rationale and request permission
                Toast.makeText(
                    this,
                    "Camera access is needed to scan QR codes on tickets",
                    Toast.LENGTH_LONG
                ).show()
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                // Request permission directly
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startQRCodeScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan QR code on your ticket")
            setCameraId(0) // Use rear camera
            setBeepEnabled(true)
            setBarcodeImageEnabled(true)
            setOrientationLocked(false)
        }
        qrScannerLauncher.launch(options)
    }

    private fun displayScannedInfo(qrContent: String) {
        // Parse and display QR code content
        val displayText = parseTicketInfo(qrContent)
        scannedInfoTextView.text = displayText
        scannedInfoTextView.visibility = TextView.VISIBLE
    }

    private fun parseTicketInfo(qrContent: String): String {
        // Parse the QR code content and format it for display
        return try {
            // If QR contains JSON or structured data, parse it
            if (qrContent.startsWith("{") && qrContent.endsWith("}")) {
                // JSON format - you can use a JSON parser here
                "Ticket Information:\n\n$qrContent"
            } else if (qrContent.contains("|") || qrContent.contains(",")) {
                // Delimiter-separated format
                val parts = qrContent.split("|", ",")
                val formatted = StringBuilder("Ticket Information:\n\n")
                parts.forEachIndexed { index, part ->
                    formatted.append("Field ${index + 1}: ${part.trim()}\n")
                }
                formatted.toString()
            } else {
                // Plain text or unknown format
                "Ticket Information:\n\n$qrContent"
            }
        } catch (e: Exception) {
            "Scanned Content:\n\n$qrContent"
        }
    }

    private fun clearScannedInfo() {
        scannedInfoTextView.text = ""
        scannedInfoTextView.visibility = TextView.GONE
    }
}