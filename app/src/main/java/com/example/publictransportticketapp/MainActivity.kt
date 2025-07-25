// MainActivity.kt
package com.fatih.publictransportticketapp

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
import androidx.appcompat.app.AlertDialog
import android.view.LayoutInflater
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import android.view.ViewGroup
import androidx.cardview.widget.CardView

// Firestore imports
import com.google.firebase.firestore.*
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

data class Ticket(
    val ticketId: String = "",
    val cardId: String = "",
    val price: Number = 0.0, // Changed to Number to handle both Int and Double from Firestore
    val purchaseTime: Number = 0L, // Changed to Number to handle both Int and Long from Firestore
    val validUntil: Number = 0L, // Changed to Number to handle both Int and Long from Firestore
    val status: String = "",
    val qrCode: String = ""
) {
    // Helper functions to get proper types
    fun getPriceAsDouble(): Double = price.toDouble()
    fun getPurchaseTimeAsLong(): Long = purchaseTime.toLong()
    fun getValidUntilAsLong(): Long = validUntil.toLong()
}

class TicketAdapter(private var tickets: List<Ticket>) : RecyclerView.Adapter<TicketAdapter.TicketViewHolder>() {

    class TicketViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ticketIdText: TextView = view.findViewById(R.id.ticketIdText)
        val priceText: TextView = view.findViewById(R.id.priceText)
        val purchaseTimeText: TextView = view.findViewById(R.id.purchaseTimeText)
        val validUntilText: TextView = view.findViewById(R.id.validUntilText)
        val statusText: TextView = view.findViewById(R.id.statusText)
        val ticketCard: CardView = view.findViewById(R.id.ticketCard)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TicketViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ticket, parent, false)
        return TicketViewHolder(view)
    }

    override fun onBindViewHolder(holder: TicketViewHolder, position: Int) {
        val ticket = tickets[position]
        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

        holder.ticketIdText.text = ticket.ticketId
        holder.priceText.text = "€${String.format("%.2f", ticket.getPriceAsDouble())}"
        holder.purchaseTimeText.text = "Purchased: ${dateFormat.format(Date(ticket.getPurchaseTimeAsLong()))}"
        holder.validUntilText.text = "Valid until: ${dateFormat.format(Date(ticket.getValidUntilAsLong()))}"

        // Set status and color
        holder.statusText.text = ticket.status.uppercase()
        val currentTime = System.currentTimeMillis()
        val isExpired = currentTime > ticket.getValidUntilAsLong()

        if (isExpired) {
            holder.statusText.text = "EXPIRED"
            holder.statusText.setTextColor(android.graphics.Color.RED)
            holder.ticketCard.alpha = 0.6f
        } else {
            holder.statusText.setTextColor(android.graphics.Color.GREEN)
            holder.ticketCard.alpha = 1.0f
        }
    }

    override fun getItemCount() = tickets.size

    fun updateTickets(newTickets: List<Ticket>) {
        tickets = newTickets
        notifyDataSetChanged()
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var scannedInfoTextView: TextView
    private lateinit var firestore: FirebaseFirestore
    private lateinit var ticketsRecyclerView: RecyclerView
    private lateinit var ticketAdapter: TicketAdapter
    private lateinit var noTicketsText: TextView
    private val ticketPrice = 10.0 // 10 euros
    private var ticketsListener: ListenerRegistration? = null

    companion object {
        private const val TAG = "MainActivity"
    }

    // QR Code scanner launcher
    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents == null) {
            Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show()
        } else {
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

        // Initialize Firestore
        firestore = Firebase.firestore

        // Test Firestore connection
        testFirestoreConnection()

        setupUI()
        setupTicketsRecyclerView()
        loadUserTickets()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up the listener to prevent memory leaks
        ticketsListener?.remove()
    }

    private fun setupTicketsRecyclerView() {
        ticketsRecyclerView = findViewById(R.id.ticketsRecyclerView)
        noTicketsText = findViewById(R.id.noTicketsText)

        ticketAdapter = TicketAdapter(emptyList())
        ticketsRecyclerView.adapter = ticketAdapter
        ticketsRecyclerView.layoutManager = LinearLayoutManager(this)

        Log.d(TAG, "RecyclerView setup completed")
    }

    private fun loadUserTickets() {
        Log.d(TAG, "Loading user tickets...")

        // Remove existing listener if any
        ticketsListener?.remove()

        // Set up real-time listener with better error handling
        ticketsListener = firestore.collection("user_tickets")
            .orderBy("purchaseTime", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "Error loading tickets: ${error.message}", error)
                    showError("Failed to load tickets: ${error.message}")
                    updateTicketsDisplay(emptyList()) // Show empty state on error
                    return@addSnapshotListener
                }

                if (snapshots != null) {
                    Log.d(TAG, "=== FIRESTORE SNAPSHOT UPDATE ===")
                    Log.d(TAG, "Raw snapshots size: ${snapshots.size()}")
                    Log.d(TAG, "Snapshots empty: ${snapshots.isEmpty}")
                    Log.d(TAG, "Snapshot metadata - hasPendingWrites: ${snapshots.metadata.hasPendingWrites()}")
                    Log.d(TAG, "Snapshot metadata - isFromCache: ${snapshots.metadata.isFromCache}")

                    // Log each document in detail
                    snapshots.documents.forEachIndexed { index, doc ->
                        Log.d(TAG, "Document $index:")
                        Log.d(TAG, "  ID: ${doc.id}")
                        Log.d(TAG, "  Exists: ${doc.exists()}")
                        Log.d(TAG, "  Data: ${doc.data}")
                        Log.d(TAG, "  Fields: ${doc.data?.keys}")
                    }

                    val tickets = mutableListOf<Ticket>()

                    for (doc in snapshots.documents) {
                        try {
                            if (doc.exists()) {
                                val ticket = doc.toObject(Ticket::class.java)
                                if (ticket != null) {
                                    Log.d(TAG, "✅ Successfully parsed ticket: ${ticket.ticketId}")
                                    tickets.add(ticket)
                                } else {
                                    Log.w(TAG, "⚠️ Failed to parse document ${doc.id} to Ticket object")
                                }
                            } else {
                                Log.w(TAG, "⚠️ Document ${doc.id} does not exist")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error parsing ticket from document ${doc.id}: ${e.message}", e)
                        }
                    }

                    Log.d(TAG, "Final parsed tickets count: ${tickets.size}")
                    Log.d(TAG, "=== END SNAPSHOT UPDATE ===")

                    // Update UI on main thread
                    runOnUiThread {
                        updateTicketsDisplay(tickets)
                    }
                } else {
                    Log.w(TAG, "Received null snapshots")
                    updateTicketsDisplay(emptyList())
                }
            }
    }

    private fun updateTicketsDisplay(tickets: List<Ticket>) {
        Log.d(TAG, "updateTicketsDisplay called with ${tickets.size} tickets")

        if (tickets.isEmpty()) {
            Log.d(TAG, "No tickets - showing empty state")
            ticketsRecyclerView.visibility = View.GONE
            noTicketsText.visibility = View.VISIBLE
            noTicketsText.text = "No tickets found"
        } else {
            Log.d(TAG, "Showing ${tickets.size} tickets")
            ticketsRecyclerView.visibility = View.VISIBLE
            noTicketsText.visibility = View.GONE
            ticketAdapter.updateTickets(tickets)

            // Log each ticket being displayed
            tickets.forEachIndexed { index, ticket ->
                Log.d(TAG, "Displaying ticket $index: ${ticket.ticketId} - €${ticket.getPriceAsDouble()}")
            }
        }
    }

    private fun testFirestoreConnection() {
        Log.d(TAG, "Testing Firestore connection...")

        // Check network connectivity first
        val connectivityManager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val activeNetwork = connectivityManager.activeNetworkInfo
        val isConnected = activeNetwork?.isConnectedOrConnecting == true
        Log.d(TAG, "Network connected: $isConnected")

        if (!isConnected) {
            Log.e(TAG, "No network connection!")
            return
        }

        val testData = hashMapOf(
            "timestamp" to System.currentTimeMillis(),
            "test" to "connection_test"
        )

        firestore.collection("test-connection")
            .add(testData)
            .addOnSuccessListener { documentReference ->
                Log.d(TAG, "✅ Firestore write test successful with ID: ${documentReference.id}")
                testCreditCardsRead()
                testUserTicketsRead() // Add this test
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "❌ Firestore write test failed: ${error.message}")
                Log.e(TAG, "Error details: $error")
            }
    }

    private fun testCreditCardsRead() {
        Log.d(TAG, "Testing Firestore credit-cards collection read...")

        firestore.collection("credit-cards")
            .get()
            .addOnSuccessListener { documents ->
                Log.d(TAG, "✅ Firestore credit-cards read test successful")
                Log.d(TAG, "Found ${documents.size()} documents in credit-cards collection")

                if (documents.isEmpty) {
                    Log.w(TAG, "⚠️ No credit-cards data found in Firestore")
                } else {
                    for (document in documents) {
                        Log.d(TAG, "📊 Found card: ${document.id}")
                    }
                }
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "❌ Firestore credit-cards read test failed: ${error.message}")
            }
    }

    private fun testUserTicketsRead() {
        Log.d(TAG, "Testing Firestore user_tickets collection read...")

        firestore.collection("user_tickets")
            .get()
            .addOnSuccessListener { documents ->
                Log.d(TAG, "✅ Firestore user_tickets read test successful")
                Log.d(TAG, "Found ${documents.size()} documents in user_tickets collection")

                if (documents.isEmpty) {
                    Log.w(TAG, "⚠️ No user_tickets data found in Firestore")
                } else {
                    for (document in documents) {
                        Log.d(TAG, "🎫 Found ticket: ${document.id}")
                        Log.d(TAG, "🎫 Ticket data: ${document.data}")
                    }
                }
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "❌ Firestore user_tickets read test failed: ${error.message}")
            }
    }

    private fun setupUI() {
        val purchaseButton = findViewById<Button>(R.id.purchaseButton)
        val readTicketButton = findViewById<LinearLayout>(R.id.readTicketButton)

        scannedInfoTextView = findViewById<TextView>(R.id.scannedInfoTextView)

        purchaseButton.setOnClickListener {
            Log.d(TAG, "🔘 Purchase button clicked!")
            testFirestoreBeforeDialog()
        }

        readTicketButton.setOnClickListener {
            checkCameraPermissionAndScan()
        }
    }

    private fun testFirestoreBeforeDialog() {
        Log.d(TAG, "🔥 Starting Firestore test before dialog...")

        val testData = hashMapOf(
            "timestamp" to System.currentTimeMillis(),
            "test" to "pre-dialog-test"
        )

        firestore.collection("test-connection")
            .add(testData)
            .addOnSuccessListener { documentReference ->
                Log.d(TAG, "✅ Firestore test successful! Document ID: ${documentReference.id}")
                showCreditCardDialog()
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "❌ Firestore test failed: ${error.message}")
                Toast.makeText(this, "Firestore connection failed: ${error.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun showCreditCardDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_credit_card, null)

        val cardNumberInput = dialogView.findViewById<TextInputEditText>(R.id.cardNumberInput)
        val expiryInput = dialogView.findViewById<TextInputEditText>(R.id.expiryInput)
        val cvvInput = dialogView.findViewById<TextInputEditText>(R.id.cvvInput)
        val cardHolderInput = dialogView.findViewById<TextInputEditText>(R.id.cardHolderInput)

        val cardNumberLayout = dialogView.findViewById<TextInputLayout>(R.id.cardNumberLayout)
        val expiryLayout = dialogView.findViewById<TextInputLayout>(R.id.expiryLayout)
        val cvvLayout = dialogView.findViewById<TextInputLayout>(R.id.cvvLayout)
        val cardHolderLayout = dialogView.findViewById<TextInputLayout>(R.id.cardHolderLayout)

        // Format card number input (15 digits)
        cardNumberInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val input = s.toString().replace(" ", "")
                if (input.length <= 15) {
                    // Format as XXXX XXXXXX XXXXX for 15-digit cards
                    val formatted = when {
                        input.length <= 4 -> input
                        input.length <= 10 -> "${input.substring(0, 4)} ${input.substring(4)}"
                        else -> "${input.substring(0, 4)} ${input.substring(4, 10)} ${input.substring(10)}"
                    }
                    if (formatted != s.toString()) {
                        cardNumberInput.setText(formatted)
                        cardNumberInput.setSelection(formatted.length)
                    }
                }
            }
        })

        // Format expiry date input
        expiryInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val input = s.toString().replace("/", "")
                if (input.length <= 4) {
                    val formatted = if (input.length >= 2) {
                        "${input.substring(0, 2)}/${input.substring(2)}"
                    } else input
                    if (formatted != s.toString()) {
                        expiryInput.setText(formatted)
                        expiryInput.setSelection(formatted.length)
                    }
                }
            }
        })

        val dialog = AlertDialog.Builder(this, R.style.CustomAlertDialog)
            .setTitle("Enter Credit Card Details")
            .setView(dialogView)
            .setPositiveButton("Purchase (€$ticketPrice)", null)
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .create()

        dialog.setOnShowListener {
            val purchaseBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            purchaseBtn.setOnClickListener {
                val cardNumber = cardNumberInput.text.toString().replace(" ", "")
                val expiry = expiryInput.text.toString()
                val cvv = cvvInput.text.toString()
                val cardHolder = cardHolderInput.text.toString()

                // Validate inputs
                var isValid = true

                if (cardNumber.length != 15 || !cardNumber.all { it.isDigit() }) {
                    cardNumberLayout.error = "Invalid card number (15 digits required)"
                    isValid = false
                } else {
                    cardNumberLayout.error = null
                }

                if (expiry.length != 5 || !expiry.matches(Regex("\\d{2}/\\d{2}"))) {
                    expiryLayout.error = "Invalid expiry date (MM/YY)"
                    isValid = false
                } else {
                    // Check if expiry date is not in the past
                    val parts = expiry.split("/")
                    val month = parts[0].toInt()
                    val year = 2000 + parts[1].toInt()
                    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                    val currentMonth = Calendar.getInstance().get(Calendar.MONTH) + 1

                    if (year < currentYear || (year == currentYear && month < currentMonth)) {
                        expiryLayout.error = "Card has expired"
                        isValid = false
                    } else if (month < 1 || month > 12) {
                        expiryLayout.error = "Invalid month"
                        isValid = false
                    } else {
                        expiryLayout.error = null
                    }
                }

                if (cvv.length != 4 || !cvv.all { it.isDigit() }) {
                    cvvLayout.error = "Invalid CVV (4 digits required)"
                    isValid = false
                } else {
                    cvvLayout.error = null
                }

                if (cardHolder.trim().isEmpty()) {
                    cardHolderLayout.error = "Card holder name required"
                    isValid = false
                } else {
                    cardHolderLayout.error = null
                }

                if (isValid) {
                    validatePaymentWithFirestore(cardNumber, expiry, cvv, cardHolder, dialog)
                }
            }
        }

        dialog.show()
    }

    private fun validatePaymentWithFirestore(
        cardNumber: String,
        expiry: String,
        cvv: String,
        cardHolder: String,
        dialog: AlertDialog
    ) {
        Log.d(TAG, "Starting payment validation with Firestore...")
        Log.d(TAG, "Input card number: $cardNumber")
        Log.d(TAG, "Input CVV: $cvv")
        Log.d(TAG, "Input expiry: $expiry")

        // Show loading
        val purchaseBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        purchaseBtn.text = "Processing..."
        purchaseBtn.isEnabled = false

        // Parse expiry date
        val expiryParts = expiry.split("/")
        val inputMonth = expiryParts[0].toInt()
        val inputYear = 2000 + expiryParts[1].toInt()

        Log.d(TAG, "Parsed month: $inputMonth, year: $inputYear")
        Log.d(TAG, "Querying Firestore database...")

        // Add timeout mechanism
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            Log.e(TAG, "Firestore query timeout - no response after 10 seconds")
            showError("Connection timeout. Please check your internet connection.")
            resetPurchaseButton(purchaseBtn)
        }
        handler.postDelayed(timeoutRunnable, 10000) // 10 second timeout

        // Query Firestore for matching card
        firestore.collection("credit-cards")
            .whereEqualTo("card_number", cardNumber.toLong())
            .whereEqualTo("cvv", cvv.toInt())
            .whereEqualTo("expiration_month", inputMonth)
            .whereEqualTo("expiration_year", inputYear)
            .get()
            .addOnSuccessListener { documents ->
                handler.removeCallbacks(timeoutRunnable)
                Log.d(TAG, "Firestore query successful")
                Log.d(TAG, "Found ${documents.size()} matching documents")

                if (documents.isEmpty) {
                    Log.d(TAG, "No matching card found")
                    showError("Invalid card details. Please check your information.")
                    resetPurchaseButton(purchaseBtn)
                } else {
                    // Found matching card
                    val cardDoc = documents.first()
                    val balance = (cardDoc.data["balance"] as? Number)?.toDouble() ?: 0.0

                    Log.d(TAG, "Card balance: $balance")

                    if (balance >= ticketPrice) {
                        processFirestorePayment(cardDoc.id, balance, dialog)
                    } else {
                        showError("Insufficient funds. Balance: €${"%.2f".format(balance)}")
                        resetPurchaseButton(purchaseBtn)
                    }
                }
            }
            .addOnFailureListener { error ->
                handler.removeCallbacks(timeoutRunnable)
                Log.e(TAG, "Firestore query error: ${error.message}")
                showError("Database connection error. Please try again.")
                resetPurchaseButton(purchaseBtn)
            }
    }

    private fun processFirestorePayment(cardId: String, currentBalance: Double, dialog: AlertDialog) {
        val newBalance = currentBalance - ticketPrice

        // Update balance in Firestore
        firestore.collection("credit-cards").document(cardId)
            .update("balance", newBalance.toInt())
            .addOnSuccessListener {
                Log.d(TAG, "✅ Card balance updated successfully")
                createFirestoreTicket(cardId, dialog)
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "❌ Failed to update card balance: ${error.message}")
                showError("Payment failed: ${error.message}")
                resetPurchaseButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE))
            }
    }

    private fun createFirestoreTicket(cardId: String, dialog: AlertDialog) {
        val ticketId = "TICKET_${System.currentTimeMillis()}"
        val purchaseTime = System.currentTimeMillis()
        val validUntil = purchaseTime + 24 * 60 * 60 * 1000 // Valid for 24 hours
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val qrCode = "$ticketId|$ticketPrice|${dateFormat.format(Date(purchaseTime))}|active"

        val ticketData = hashMapOf(
            "ticketId" to ticketId,
            "cardId" to cardId,
            "price" to ticketPrice,
            "purchaseTime" to purchaseTime,
            "validUntil" to validUntil,
            "status" to "active",
            "qrCode" to qrCode
        )

        Log.d(TAG, "Creating ticket with data: $ticketData")

        firestore.collection("user_tickets").document(ticketId)
            .set(ticketData)
            .addOnSuccessListener {
                Log.d(TAG, "✅ Ticket created successfully with ID: $ticketId")
                dialog.dismiss()
                showSuccessDialog(ticketId)
                // The ticket list will update automatically due to the real-time listener
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "❌ Ticket creation failed: ${error.message}")
                showError("Ticket creation failed: ${error.message}")
                resetPurchaseButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE))
            }
    }

    private fun showSuccessDialog(ticketId: String) {
        AlertDialog.Builder(this, R.style.CustomAlertDialog)
            .setTitle("Purchase Successful!")
            .setMessage("Ticket purchased successfully!\n\nTicket ID: $ticketId\nPrice: €$ticketPrice\nValid for 24 hours")
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Log.e(TAG, "Error shown to user: $message")
    }

    private fun resetPurchaseButton(button: Button) {
        button.text = "Purchase (€$ticketPrice)"
        button.isEnabled = true
    }

    // QR Code scanning methods (unchanged)
    private fun checkCameraPermissionAndScan() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                startQRCodeScanner()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Toast.makeText(this, "Camera access is needed to scan QR codes on tickets", Toast.LENGTH_LONG).show()
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startQRCodeScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan QR code on your ticket")
            setCameraId(0)
            setBeepEnabled(true)
            setBarcodeImageEnabled(true)
            setOrientationLocked(false)
        }
        qrScannerLauncher.launch(options)
    }

    private fun displayScannedInfo(qrContent: String) {
        val displayText = parseTicketInfo(qrContent)
        scannedInfoTextView.text = displayText
        scannedInfoTextView.visibility = TextView.VISIBLE
    }

    private fun parseTicketInfo(qrContent: String): String {
        return try {
            if (qrContent.startsWith("{") && qrContent.endsWith("}")) {
                "Ticket Information:\n\n$qrContent"
            } else if (qrContent.contains("|") || qrContent.contains(",")) {
                val parts = qrContent.split("|", ",")
                val formatted = StringBuilder("Ticket Information:\n\n")
                parts.forEachIndexed { index, part ->
                    formatted.append("Field ${index + 1}: ${part.trim()}\n")
                }
                formatted.toString()
            } else {
                "Ticket Information:\n\n$qrContent"
            }
        } catch (e: Exception) {
            "Scanned Content:\n\n$qrContent"
        }
    }
}