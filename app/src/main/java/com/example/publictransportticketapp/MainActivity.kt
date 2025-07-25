// MainActivity.kt
package com.fatih.publictransportticketapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.widget.CheckBox

// Firestore imports
import com.google.firebase.firestore.*
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

data class Ticket(
    val ticketId: String = "",
    val cardId: String = "",
    val price: Number = 0.0,
    val purchaseTime: Number = 0L,
    val validUntil: Number = 0L,
    val status: String = "", // "purchased", "active", "expired"
    val qrCode: String = "",
    val activatedTime: Number = 0L // New field for when ticket was activated
) {
    fun getPriceAsDouble(): Double = price.toDouble()
    fun getPurchaseTimeAsLong(): Long = purchaseTime.toLong()
    fun getValidUntilAsLong(): Long = validUntil.toLong()
    fun getActivatedTimeAsLong(): Long = activatedTime.toLong()

    // Helper function to get remaining time in milliseconds
    fun getRemainingTimeMs(): Long {
        if (status != "active" || activatedTime.toLong() == 0L) return 0L
        val activeUntil = activatedTime.toLong() + (30 * 60 * 1000) // 30 minutes
        return maxOf(0L, activeUntil - System.currentTimeMillis())
    }

    // Helper function to check if ticket should be expired
    fun shouldBeExpired(): Boolean {
        return status == "active" && getRemainingTimeMs() <= 0L
    }
}
// Add this data class after the Ticket data class
data class SavedCreditCard(
    val id: String = "",
    val cardNumber: String = "",
    val expiryMonth: Int = 0,
    val expiryYear: Int = 0,
    val cvv: String = "",
    val cardHolderName: String = "",
    val nickname: String = "", // User-friendly name for the card
    val lastFourDigits: String = "",
    val savedAt: Long = 0L
) {
    // Helper function to get masked card number
    fun getMaskedCardNumber(): String {
        return if (cardNumber.length >= 4) {
            "**** **** **** ${cardNumber.takeLast(4)}"
        } else {
            "**** **** **** ****"
        }
    }

    // Helper function to get expiry in MM/YY format
    fun getFormattedExpiry(): String {
        return String.format("%02d/%02d", expiryMonth, expiryYear % 100)
    }
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
        Log.d("TicketAdapter", "onCreateViewHolder called")
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ticket, parent, false)
        return TicketViewHolder(view)
    }

    override fun onBindViewHolder(holder: TicketViewHolder, position: Int) {
        Log.d("TicketAdapter", "onBindViewHolder called for position $position of ${tickets.size} tickets")

        if (position >= tickets.size) {
            Log.e("TicketAdapter", "❌ Position $position is out of bounds for tickets list of size ${tickets.size}")
            return
        }

        val ticket = tickets[position]
        Log.d("TicketAdapter", "Binding ticket: ${ticket.ticketId}")

        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

        holder.ticketIdText.text = ticket.ticketId
        holder.priceText.text = "€${String.format("%.2f", ticket.getPriceAsDouble())}"
        holder.purchaseTimeText.text = "Purchased: ${dateFormat.format(Date(ticket.getPurchaseTimeAsLong()))}"

        // Handle different ticket statuses
        when (ticket.status) {
            "purchased" -> {
                holder.statusText.text = "PURCHASED"
                holder.statusText.setTextColor(android.graphics.Color.BLUE)
                holder.validUntilText.text = "Ready to activate"
                holder.ticketCard.alpha = 1.0f
            }
            "active" -> {
                val remainingMs = ticket.getRemainingTimeMs()
                if (remainingMs > 0) {
                    val minutes = (remainingMs / (1000 * 60)).toInt()
                    val seconds = ((remainingMs % (1000 * 60)) / 1000).toInt()

                    holder.statusText.text = "ACTIVE"
                    holder.statusText.setTextColor(android.graphics.Color.GREEN)
                    holder.validUntilText.text = "Time remaining: ${String.format("%02d:%02d", minutes, seconds)}"
                    holder.ticketCard.alpha = 1.0f
                } else {
                    // Should be expired
                    holder.statusText.text = "EXPIRED"
                    holder.statusText.setTextColor(android.graphics.Color.RED)
                    holder.validUntilText.text = "Ticket expired"
                    holder.ticketCard.alpha = 0.6f
                }
            }
            "expired" -> {
                holder.statusText.text = "EXPIRED"
                holder.statusText.setTextColor(android.graphics.Color.RED)
                holder.validUntilText.text = "Ticket expired"
                holder.ticketCard.alpha = 0.6f
            }
            else -> {
                holder.statusText.text = ticket.status.uppercase()
                holder.statusText.setTextColor(android.graphics.Color.GRAY)
                holder.validUntilText.text = "Unknown status"
                holder.ticketCard.alpha = 0.8f
            }
        }

        Log.d("TicketAdapter", "✅ Successfully bound ticket ${ticket.ticketId} at position $position")
    }

    override fun getItemCount(): Int {
        Log.d("TicketAdapter", "getItemCount() called, returning: ${tickets.size}")
        return tickets.size
    }

    fun updateTickets(newTickets: List<Ticket>) {
        Log.d("TicketAdapter", "updateTickets called with ${newTickets.size} tickets")

        // Log each ticket being set
        newTickets.forEachIndexed { index, ticket ->
            Log.d("TicketAdapter", "Ticket $index: ${ticket.ticketId}")
        }

        tickets = newTickets
        notifyDataSetChanged()

        Log.d("TicketAdapter", "Adapter now has ${tickets.size} tickets")
        Log.d("TicketAdapter", "getItemCount() returns: ${getItemCount()}")
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

    // Timer for updating active tickets countdown
    private var countdownHandler: android.os.Handler? = null
    private var countdownRunnable: Runnable? = null

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

        // Start countdown timer for active tickets
        startTicketCountdownTimer()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up the listener to prevent memory leaks
        ticketsListener?.remove()
        stopTicketCountdownTimer() // Clean up countdown timer
    }

    private fun startTicketCountdownTimer() {
        stopTicketCountdownTimer() // Stop any existing timer

        countdownHandler = android.os.Handler(android.os.Looper.getMainLooper())
        countdownRunnable = object : Runnable {
            override fun run() {
                // Update the adapter to refresh countdown timers
                ticketAdapter.notifyDataSetChanged()

                // Check for tickets that should be expired and update them
                checkAndExpireTickets()

                // Schedule next update in 1 second
                countdownHandler?.postDelayed(this, 1000)
            }
        }
        countdownHandler?.post(countdownRunnable!!)
    }

    private fun stopTicketCountdownTimer() {
        countdownRunnable?.let { countdownHandler?.removeCallbacks(it) }
        countdownHandler = null
        countdownRunnable = null
    }

    private fun checkAndExpireTickets() {
        // This will be handled by the real-time listener in loadUserTickets
        // We could add logic here to update expired tickets in Firestore if needed
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
                                Log.d(TAG, "Processing document ${doc.id}")
                                Log.d(TAG, "Document data: ${doc.data}")

                                // Manual parsing to debug the issue
                                val data = doc.data
                                if (data != null) {
                                    try {
                                        val ticket = Ticket(
                                            ticketId = data["ticketId"] as? String ?: "",
                                            cardId = data["cardId"] as? String ?: "",
                                            price = data["price"] as? Number ?: 0.0,
                                            purchaseTime = data["purchaseTime"] as? Number ?: 0L,
                                            validUntil = data["validUntil"] as? Number ?: 0L,
                                            status = data["status"] as? String ?: "",
                                            qrCode = data["qrCode"] as? String ?: "",
                                            activatedTime = data["activatedTime"] as? Number ?: 0L
                                        )

                                        Log.d(TAG, "✅ Manually parsed ticket: $ticket")
                                        tickets.add(ticket)

                                    } catch (e: Exception) {
                                        Log.e(TAG, "❌ Manual parsing failed for ${doc.id}: ${e.message}")

                                        // Try automatic parsing as fallback
                                        val ticket = doc.toObject(Ticket::class.java)
                                        if (ticket != null) {
                                            Log.d(TAG, "✅ Fallback auto-parsing successful: ${ticket.ticketId}")
                                            tickets.add(ticket)
                                        } else {
                                            Log.w(TAG, "⚠️ Both manual and auto parsing failed for ${doc.id}")
                                        }
                                    }
                                } else {
                                    Log.w(TAG, "⚠️ Document ${doc.id} has null data")
                                }
                            } else {
                                Log.w(TAG, "⚠️ Document ${doc.id} does not exist")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error processing document ${doc.id}: ${e.message}", e)
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

                // Check if user has saved cards first
                loadSavedCards { savedCards ->
                    if (savedCards.isNotEmpty()) {
                        // Show option to choose between saved cards or new card
                        AlertDialog.Builder(this, R.style.CustomAlertDialog)
                            .setTitle("Choose Payment Method")
                            .setMessage("How would you like to pay?")
                            .setPositiveButton("Use Saved Card (${savedCards.size} available)") { dialog, _ ->
                                dialog.dismiss()
                                showSavedCardsDialog()
                            }
                            .setNeutralButton("Enter New Card") { dialog, _ ->
                                dialog.dismiss()
                                showCreditCardDialog()
                            }
                            .setNegativeButton("Cancel") { dialog, _ ->
                                dialog.dismiss()
                            }
                            .show()
                    } else {
                        // No saved cards, go directly to manual entry
                        showCreditCardDialog()
                    }
                }
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

        // Add checkbox for saving card
        val saveCardCheckBox = CheckBox(this).apply {
            text = "Save this card for future use"
            isChecked = false
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.black))
        }

        // Add nickname input (initially hidden)
        val nicknameInput = TextInputEditText(this).apply {
            hint = "Card nickname (optional)"
            visibility = View.GONE
        }
        val nicknameLayout = TextInputLayout(this).apply {
            hint = "Card nickname (optional)"
            addView(nicknameInput)
            visibility = View.GONE
        }

        // Add views to dialog layout
        if (dialogView is ViewGroup) {
            dialogView.addView(saveCardCheckBox)
            dialogView.addView(nicknameLayout)
        }

        // Show/hide nickname input based on checkbox
        saveCardCheckBox.setOnCheckedChangeListener { _, isChecked ->
            nicknameLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            nicknameInput.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

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
            .setNeutralButton("Use Saved Card") { _, _ ->
                showSavedCardsDialog()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .create()

        dialog.setOnShowListener {
            val purchaseBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            purchaseBtn.setOnClickListener {
                val cardNumber = cardNumberInput.text.toString().replace(" ", "")
                val expiry = expiryInput.text.toString()
                val cvv = cvvInput.text.toString()
                val cardHolder = cardHolderInput.text.toString()
                val shouldSaveCard = saveCardCheckBox.isChecked
                val nickname = nicknameInput.text.toString().trim()

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
                    validatePaymentWithFirestore(cardNumber, expiry, cvv, cardHolder, dialog, shouldSaveCard, nickname)
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
        dialog: AlertDialog?,
        shouldSaveCard: Boolean = false,
        nickname: String = ""
    ) {
        Log.d(TAG, "Starting payment validation with Firestore...")
        Log.d(TAG, "Input card number: $cardNumber")
        Log.d(TAG, "Input CVV: $cvv")
        Log.d(TAG, "Input expiry: $expiry")
        Log.d(TAG, "Should save card: $shouldSaveCard")

        // Show loading
        dialog?.let { d ->
            val purchaseBtn = d.getButton(AlertDialog.BUTTON_POSITIVE)
            purchaseBtn.text = "Processing..."
            purchaseBtn.isEnabled = false
        }

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
            dialog?.let { resetPurchaseButton(it.getButton(AlertDialog.BUTTON_POSITIVE)) }
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
                    dialog?.let { resetPurchaseButton(it.getButton(AlertDialog.BUTTON_POSITIVE)) }
                } else {
                    // Found matching card
                    val cardDoc = documents.first()
                    val balance = (cardDoc.data["balance"] as? Number)?.toDouble() ?: 0.0

                    Log.d(TAG, "Card balance: $balance")

                    if (balance >= ticketPrice) {
                        // If user wants to save the card, save it first
                        if (shouldSaveCard) {
                            saveCardToFirestore(cardNumber, expiry, cvv, cardHolder, nickname) { saved ->
                                if (saved) {
                                    Log.d(TAG, "✅ Card saved successfully before payment")
                                } else {
                                    Log.w(TAG, "⚠️ Failed to save card, but continuing with payment")
                                }
                                // Continue with payment regardless of save result
                                processFirestorePayment(cardDoc.id, balance, dialog)
                            }
                        } else {
                            processFirestorePayment(cardDoc.id, balance, dialog)
                        }
                    } else {
                        showError("Insufficient funds. Balance: €${"%.2f".format(balance)}")
                        dialog?.let { resetPurchaseButton(it.getButton(AlertDialog.BUTTON_POSITIVE)) }
                    }
                }
            }
            .addOnFailureListener { error ->
                handler.removeCallbacks(timeoutRunnable)
                Log.e(TAG, "Firestore query error: ${error.message}")
                showError("Database connection error. Please try again.")
                dialog?.let { resetPurchaseButton(it.getButton(AlertDialog.BUTTON_POSITIVE)) }
            }
    }


    private fun processFirestorePayment(cardId: String, currentBalance: Double, dialog: AlertDialog?) {
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
                dialog?.let { resetPurchaseButton(it.getButton(AlertDialog.BUTTON_POSITIVE)) }
            }
    }

    private fun createFirestoreTicket(cardId: String, dialog: AlertDialog?) {
        val ticketId = "TICKET_${System.currentTimeMillis()}"
        val purchaseTime = System.currentTimeMillis()
        val validUntil = purchaseTime + 24 * 60 * 60 * 1000 // Valid for 24 hours
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val qrCode = "$ticketId|$ticketPrice|${dateFormat.format(Date(purchaseTime))}|purchased"

        val ticketData = hashMapOf(
            "ticketId" to ticketId,
            "cardId" to cardId,
            "price" to ticketPrice,
            "purchaseTime" to purchaseTime,
            "validUntil" to validUntil,
            "status" to "purchased", // Changed from "active" to "purchased"
            "qrCode" to qrCode,
            "activatedTime" to 0L // Not activated yet
        )

        Log.d(TAG, "Creating ticket with data: $ticketData")

        firestore.collection("user_tickets").document(ticketId)
            .set(ticketData)
            .addOnSuccessListener {
                Log.d(TAG, "✅ Ticket created successfully with ID: $ticketId")
                dialog?.dismiss()
                showSuccessDialog(ticketId)
                // The ticket list will update automatically due to the real-time listener
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "❌ Ticket creation failed: ${error.message}")
                showError("Ticket creation failed: ${error.message}")
                dialog?.let { resetPurchaseButton(it.getButton(AlertDialog.BUTTON_POSITIVE)) }
            }
    }

    private fun showSuccessDialog(ticketId: String) {
        AlertDialog.Builder(this, R.style.CustomAlertDialog)
            .setTitle("Purchase Successful!")
            .setMessage("Ticket purchased successfully!\n\nTicket ID: $ticketId\nPrice: €$ticketPrice\nStatus: PURCHASED\n\nScan a QR code to activate your ticket.")
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

    // QR Code scanning methods
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
        // Show loading state
        scannedInfoTextView.text = "Validating QR code..."
        scannedInfoTextView.visibility = TextView.VISIBLE

        // Query Firestore to check if this QR code exists and get its type
        validateQRCodeWithFirestore(qrContent)
    }

    private fun validateQRCodeWithFirestore(qrContent: String) {
        Log.d(TAG, "Validating QR code: $qrContent")

        // Add timeout mechanism
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            Log.e(TAG, "QR validation timeout")
            scannedInfoTextView.text = "Validation Timeout\n\nPlease check your internet connection and try again."
            scannedInfoTextView.setTextColor(android.graphics.Color.RED)
        }
        handler.postDelayed(timeoutRunnable, 10000) // 10 second timeout

        // Try to get all documents first, then filter manually
        firestore.collection("qr-codes")
            .get()
            .addOnSuccessListener { documents ->
                handler.removeCallbacks(timeoutRunnable)
                Log.d(TAG, "QR codes collection read successful")
                Log.d(TAG, "Found ${documents.size()} total QR codes in database")

                // Manual filtering to avoid index issues
                var foundMatch = false
                var matchedType = ""

                for (document in documents) {
                    try {
                        val docCode = document.data["code"] as? String
                        val docType = document.data["type"] as? String

                        Log.d(TAG, "Checking document ${document.id}: code='$docCode', type='$docType'")

                        if (docCode == qrContent) {
                            Log.d(TAG, "✅ Found matching QR code!")
                            foundMatch = true
                            matchedType = docType ?: "unknown"
                            break
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading document ${document.id}: ${e.message}")
                    }
                }

                if (foundMatch) {
                    Log.d(TAG, "Valid QR code found with type: $matchedType")
                    showTicketSelectionDialog(matchedType, qrContent)
                } else {
                    Log.d(TAG, "No matching QR code found for: $qrContent")
                    scannedInfoTextView.text = "Invalid QR Code\n\nThis QR code '$qrContent' is not registered in the system."
                    scannedInfoTextView.setTextColor(android.graphics.Color.RED)

                    // Show all available QR codes for debugging
                    if (documents.size() > 0) {
                        Log.d(TAG, "Available QR codes in database:")
                        for (doc in documents) {
                            val code = doc.data["code"] as? String
                            val type = doc.data["type"] as? String
                            Log.d(TAG, "  - Code: '$code', Type: '$type'")
                        }
                    }
                }
            }
            .addOnFailureListener { error ->
                handler.removeCallbacks(timeoutRunnable)
                Log.e(TAG, "QR validation error: ${error.message}")
                Log.e(TAG, "Error details: $error")

                val errorMessage = when {
                    error.message?.contains("PERMISSION_DENIED") == true ->
                        "Permission Error\n\nFirestore security rules don't allow reading QR codes collection."
                    error.message?.contains("FAILED_PRECONDITION") == true ->
                        "Database Error\n\nFirestore index missing. Please check Firestore console."
                    error.message?.contains("UNAVAILABLE") == true ->
                        "Connection Error\n\nFirestore service is unavailable. Please try again."
                    else ->
                        "Validation Error\n\nFailed to validate QR code: ${error.message}"
                }

                scannedInfoTextView.text = errorMessage
                scannedInfoTextView.setTextColor(android.graphics.Color.RED)
                showError("QR validation failed: ${error.message}")
            }
    }


    private fun showTicketSelectionDialog(transportType: String, qrContent: String) {
        Log.d(TAG, "=== TICKET SELECTION DEBUG ===")
        Log.d(TAG, "Looking for purchased tickets...")
        Log.d(TAG, "Transport type: $transportType")
        Log.d(TAG, "QR content: $qrContent")

        // Add timeout
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            Log.e(TAG, "Ticket loading timeout")
            showError("Timeout loading tickets. Please try again.")
        }
        handler.postDelayed(timeoutRunnable, 10000)

        // Get all tickets first, then filter manually to avoid index issues
        firestore.collection("user_tickets")
            .get()
            .addOnSuccessListener { documents ->
                handler.removeCallbacks(timeoutRunnable)
                Log.d(TAG, "User tickets loaded successfully")
                Log.d(TAG, "Found ${documents.size()} total tickets in database")

                // Log all tickets found
                documents.forEachIndexed { index, doc ->
                    Log.d(TAG, "Document $index:")
                    Log.d(TAG, "  ID: ${doc.id}")
                    Log.d(TAG, "  Data: ${doc.data}")
                    val status = doc.data["status"] as? String ?: "null"
                    Log.d(TAG, "  Status: '$status'")
                }

                val purchasedTickets = mutableListOf<Ticket>()
                val allTickets = mutableListOf<Ticket>() // For debugging

                for (doc in documents) {
                    try {
                        val data = doc.data
                        val status = data["status"] as? String ?: ""
                        val ticketId = data["ticketId"] as? String ?: ""

                        val ticket = Ticket(
                            ticketId = ticketId,
                            cardId = data["cardId"] as? String ?: "",
                            price = data["price"] as? Number ?: 0.0,
                            purchaseTime = data["purchaseTime"] as? Number ?: 0L,
                            validUntil = data["validUntil"] as? Number ?: 0L,
                            status = status,
                            qrCode = data["qrCode"] as? String ?: "",
                            activatedTime = data["activatedTime"] as? Number ?: 0L
                        )

                        allTickets.add(ticket)
                        Log.d(TAG, "Parsed ticket: ID='$ticketId', Status='$status'")

                        // Only include purchased tickets
                        if (status == "purchased") {
                            purchasedTickets.add(ticket)
                            Log.d(TAG, "✅ Added purchased ticket: ${ticket.ticketId}")
                        } else {
                            Log.d(TAG, "❌ Skipped ticket ${ticket.ticketId} with status: '$status'")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing ticket ${doc.id}: ${e.message}", e)
                    }
                }

                Log.d(TAG, "=== SUMMARY ===")
                Log.d(TAG, "Total tickets found: ${allTickets.size}")
                Log.d(TAG, "Purchased tickets found: ${purchasedTickets.size}")

                // Log status breakdown
                val statusCounts = allTickets.groupBy { it.status }.mapValues { it.value.size }
                Log.d(TAG, "Status breakdown: $statusCounts")

                if (allTickets.isEmpty()) {
                    Log.w(TAG, "❌ No tickets found at all in database")
                    scannedInfoTextView.text = "No Tickets Found\n\nNo tickets found in database.\n\nPlease purchase a ticket first."
                    scannedInfoTextView.setTextColor(android.graphics.Color.RED)
                    Toast.makeText(this, "No tickets found in database", Toast.LENGTH_LONG).show()
                } else if (purchasedTickets.isEmpty()) {
                    Log.w(TAG, "❌ No purchased tickets available")

                    // Show detailed message about available tickets
                    val availableStatuses = allTickets.map { "${it.ticketId.takeLast(6)}: ${it.status}" }.joinToString("\n")

                    scannedInfoTextView.text = "No Available Tickets\n\nYou don't have any tickets with 'purchased' status to activate.\n\nAvailable tickets:\n$availableStatuses\n\nNote: Only 'purchased' tickets can be activated."
                    scannedInfoTextView.setTextColor(android.graphics.Color.RED)
                    Toast.makeText(this, "No purchased tickets available", Toast.LENGTH_LONG).show()
                } else {
                    Log.d(TAG, "✅ Found ${purchasedTickets.size} purchased tickets, showing selection UI")
                    showTicketSelectionUI(purchasedTickets, transportType, qrContent)
                }
            }
            .addOnFailureListener { error ->
                handler.removeCallbacks(timeoutRunnable)
                Log.e(TAG, "❌ Error loading purchased tickets: ${error.message}")
                Log.e(TAG, "Error details: $error")

                val errorMessage = when {
                    error.message?.contains("PERMISSION_DENIED") == true ->
                        "Permission denied. Please check Firestore security rules."
                    error.message?.contains("FAILED_PRECONDITION") == true ->
                        "Database index missing. Please check Firestore console."
                    else ->
                        "Failed to load tickets: ${error.message}"
                }

                scannedInfoTextView.text = "Error Loading Tickets\n\n$errorMessage"
                scannedInfoTextView.setTextColor(android.graphics.Color.RED)
                showError(errorMessage)
            }
    }

    // Also add this improved version of showTicketSelectionUI
    private fun showTicketSelectionUI(tickets: List<Ticket>, transportType: String, qrContent: String) {
        Log.d(TAG, "=== SHOWING TICKET SELECTION UI ===")
        Log.d(TAG, "Tickets to display: ${tickets.size}")

        if (tickets.isEmpty()) {
            Log.e(TAG, "❌ Cannot show UI - tickets list is empty!")
            showError("No tickets to display")
            return
        }

        // Create a custom dialog with a simple layout
        runOnUiThread {
            try {
                // Create simple ticket descriptions
                val ticketDescriptions = tickets.mapIndexed { index, ticket ->
                    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                    val shortId = ticket.ticketId.takeLast(6)
                    val price = String.format("%.2f", ticket.getPriceAsDouble())
                    val purchaseDate = dateFormat.format(Date(ticket.getPurchaseTimeAsLong()))

                    "Ticket $shortId\n€$price - $purchaseDate"
                }

                Log.d(TAG, "Created ${ticketDescriptions.size} ticket descriptions")
                ticketDescriptions.forEachIndexed { index, desc ->
                    Log.d(TAG, "Description $index: $desc")
                }

                // Create a simple AlertDialog with manual button creation
                val options = ticketDescriptions.toTypedArray()

                // Use a different approach - create buttons manually
                val alertBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialog)
                    .setTitle("Activate Ticket for ${transportType.uppercase()}")
                    .setMessage("Choose a ticket to activate:")

                // Add each ticket as a clickable option in the message
                val messageBuilder = StringBuilder()
                messageBuilder.append("Choose a ticket to activate:\n\n")

                tickets.forEachIndexed { index, ticket ->
                    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                    val shortId = ticket.ticketId.takeLast(6)
                    val price = String.format("%.2f", ticket.getPriceAsDouble())
                    val purchaseDate = dateFormat.format(Date(ticket.getPurchaseTimeAsLong()))

                    messageBuilder.append("${index + 1}. Ticket $shortId\n")
                    messageBuilder.append("   €$price - $purchaseDate\n\n")
                }

                alertBuilder.setMessage(messageBuilder.toString())

                // Add buttons for each ticket
                when (tickets.size) {
                    1 -> {
                        alertBuilder.setPositiveButton("Activate Ticket") { dialog, _ ->
                            Log.d(TAG, "Activating single ticket: ${tickets[0].ticketId}")
                            activateTicket(tickets[0], transportType, qrContent)
                            dialog.dismiss()
                        }
                    }
                    2 -> {
                        alertBuilder.setPositiveButton("Activate Ticket 1") { dialog, _ ->
                            Log.d(TAG, "Activating ticket 1: ${tickets[0].ticketId}")
                            activateTicket(tickets[0], transportType, qrContent)
                            dialog.dismiss()
                        }
                        alertBuilder.setNeutralButton("Activate Ticket 2") { dialog, _ ->
                            Log.d(TAG, "Activating ticket 2: ${tickets[1].ticketId}")
                            activateTicket(tickets[1], transportType, qrContent)
                            dialog.dismiss()
                        }
                    }
                    else -> {
                        // For 3+ tickets, use a different approach
                        alertBuilder.setItems(options) { dialog, which ->
                            Log.d(TAG, "User selected ticket at index: $which")
                            if (which >= 0 && which < tickets.size) {
                                val selectedTicket = tickets[which]
                                Log.d(TAG, "Activating ticket: ${selectedTicket.ticketId}")
                                activateTicket(selectedTicket, transportType, qrContent)
                                dialog.dismiss()
                            }
                        }
                    }
                }

                alertBuilder.setNegativeButton("Cancel") { dialog, _ ->
                    Log.d(TAG, "User cancelled ticket selection")
                    dialog.dismiss()
                    scannedInfoTextView.text = "Ticket activation cancelled"
                    scannedInfoTextView.setTextColor(android.graphics.Color.GRAY)
                }

                val dialog = alertBuilder.create()
                dialog.show()

                Log.d(TAG, "✅ Ticket selection dialog shown successfully")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error showing ticket selection dialog: ${e.message}", e)
                showError("Error showing ticket selection: ${e.message}")

                // Fallback - show a simple confirmation for the first ticket
                if (tickets.isNotEmpty()) {
                    showSimpleFallbackDialog(tickets[0], transportType, qrContent)
                }
            }
        }
    }

    private fun showSimpleFallbackDialog(ticket: Ticket, transportType: String, qrContent: String) {
        Log.d(TAG, "Showing fallback dialog for ticket: ${ticket.ticketId}")

        val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
        val shortId = ticket.ticketId.takeLast(6)
        val price = String.format("%.2f", ticket.getPriceAsDouble())
        val purchaseDate = dateFormat.format(Date(ticket.getPurchaseTimeAsLong()))

        AlertDialog.Builder(this, R.style.CustomAlertDialog)
            .setTitle("Activate Ticket for ${transportType.uppercase()}")
            .setMessage("Activate this ticket?\n\nTicket $shortId\n€$price - $purchaseDate")
            .setPositiveButton("Activate") { dialog, _ ->
                Log.d(TAG, "Activating ticket from fallback: ${ticket.ticketId}")
                activateTicket(ticket, transportType, qrContent)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                Log.d(TAG, "User cancelled from fallback")
                dialog.dismiss()
                scannedInfoTextView.text = "Ticket activation cancelled"
                scannedInfoTextView.setTextColor(android.graphics.Color.GRAY)
            }
            .show()
    }


    private fun activateTicket(ticket: Ticket, transportType: String, qrContent: String) {
        val activationTime = System.currentTimeMillis()

        // Update ticket in Firestore
        firestore.collection("user_tickets").document(ticket.ticketId)
            .update(
                mapOf(
                    "status" to "active",
                    "activatedTime" to activationTime
                )
            )
            .addOnSuccessListener {
                Log.d(TAG, "✅ Ticket ${ticket.ticketId} activated successfully")

                val displayText = when (transportType.lowercase()) {
                    "ferry" -> "🚢 FERRY TICKET ACTIVATED\n\nTicket: ${ticket.ticketId.takeLast(6)}\n30 minutes remaining"
                    "metro" -> "🚇 METRO TICKET ACTIVATED\n\nTicket: ${ticket.ticketId.takeLast(6)}\n30 minutes remaining"
                    "bus" -> "🚌 BUS TICKET ACTIVATED\n\nTicket: ${ticket.ticketId.takeLast(6)}\n30 minutes remaining"
                    else -> "✅ TICKET ACTIVATED\n\nTicket: ${ticket.ticketId.takeLast(6)}\n30 minutes remaining"
                }

                scannedInfoTextView.text = displayText
                scannedInfoTextView.setTextColor(android.graphics.Color.GREEN)

                Toast.makeText(this, "Ticket activated! Valid for 30 minutes.", Toast.LENGTH_LONG).show()

                // Start automatic expiration after 30 minutes
                scheduleTicketExpiration(ticket.ticketId)
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "❌ Failed to activate ticket: ${error.message}")
                showError("Failed to activate ticket: ${error.message}")
            }
    }

    private fun scheduleTicketExpiration(ticketId: String) {
        // Schedule automatic expiration after 30 minutes
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            expireTicket(ticketId)
        }, 30 * 60 * 1000) // 30 minutes
    }

    private fun expireTicket(ticketId: String) {
        firestore.collection("user_tickets").document(ticketId)
            .update("status", "expired")
            .addOnSuccessListener {
                Log.d(TAG, "✅ Ticket $ticketId expired automatically")
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "❌ Failed to expire ticket $ticketId: ${error.message}")
            }
    }
    // Add these functions to your MainActivity class

    private fun loadSavedCards(callback: (List<SavedCreditCard>) -> Unit) {
        Log.d(TAG, "Loading saved credit cards...")

        firestore.collection("saved_credit_cards")
            .orderBy("savedAt", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { documents ->
                val savedCards = mutableListOf<SavedCreditCard>()

                for (doc in documents) {
                    try {
                        val data = doc.data
                        val card = SavedCreditCard(
                            id = doc.id,
                            cardNumber = data["cardNumber"] as? String ?: "",
                            expiryMonth = (data["expiryMonth"] as? Number)?.toInt() ?: 0,
                            expiryYear = (data["expiryYear"] as? Number)?.toInt() ?: 0,
                            cvv = data["cvv"] as? String ?: "",
                            cardHolderName = data["cardHolderName"] as? String ?: "",
                            nickname = data["nickname"] as? String ?: "",
                            lastFourDigits = data["lastFourDigits"] as? String ?: "",
                            savedAt = (data["savedAt"] as? Number)?.toLong() ?: 0L
                        )
                        savedCards.add(card)
                        Log.d(TAG, "Loaded saved card: ${card.nickname} - ${card.getMaskedCardNumber()}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing saved card ${doc.id}: ${e.message}")
                    }
                }

                Log.d(TAG, "Loaded ${savedCards.size} saved cards")
                callback(savedCards)
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Error loading saved cards: ${error.message}")
                callback(emptyList())
            }
    }

    private fun saveCardToFirestore(
        cardNumber: String,
        expiry: String,
        cvv: String,
        cardHolder: String,
        nickname: String,
        callback: (Boolean) -> Unit
    ) {
        val expiryParts = expiry.split("/")
        val month = expiryParts[0].toInt()
        val year = 2000 + expiryParts[1].toInt()

        val cardData = hashMapOf(
            "cardNumber" to cardNumber,
            "expiryMonth" to month,
            "expiryYear" to year,
            "cvv" to cvv,
            "cardHolderName" to cardHolder,
            "nickname" to nickname.ifEmpty { "Card ending in ${cardNumber.takeLast(4)}" },
            "lastFourDigits" to cardNumber.takeLast(4),
            "savedAt" to System.currentTimeMillis()
        )

        Log.d(TAG, "Saving card with nickname: $nickname")

        firestore.collection("saved_credit_cards")
            .add(cardData)
            .addOnSuccessListener { documentReference ->
                Log.d(TAG, "✅ Card saved successfully with ID: ${documentReference.id}")
                callback(true)
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "❌ Failed to save card: ${error.message}")
                callback(false)
            }
    }

    private fun deleteSavedCard(cardId: String, callback: (Boolean) -> Unit) {
        firestore.collection("saved_credit_cards").document(cardId)
            .delete()
            .addOnSuccessListener {
                Log.d(TAG, "✅ Card deleted successfully")
                callback(true)
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "❌ Failed to delete card: ${error.message}")
                callback(false)
            }
    }

    private fun showSavedCardsDialog() {
        Log.d(TAG, "Showing saved cards dialog...")

        loadSavedCards { savedCards ->
            if (savedCards.isEmpty()) {
                Toast.makeText(this, "No saved cards found", Toast.LENGTH_SHORT).show()
                showCreditCardDialog() // Fall back to manual entry
                return@loadSavedCards
            }

            val cardDescriptions = savedCards.map { card ->
                "${card.nickname}\n${card.getMaskedCardNumber()} - ${card.getFormattedExpiry()}"
            }.toTypedArray()

            val builder = AlertDialog.Builder(this, R.style.CustomAlertDialog)
                .setTitle("Select Saved Card")
                .setItems(cardDescriptions) { dialog, which ->
                    val selectedCard = savedCards[which]
                    Log.d(TAG, "Selected saved card: ${selectedCard.nickname}")
                    processSavedCardPayment(selectedCard)
                    dialog.dismiss()
                }
                .setNeutralButton("Add New Card") { dialog, _ ->
                    dialog.dismiss()
                    showCreditCardDialog()
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    dialog.dismiss()
                }

            // Add option to manage cards if there are saved cards
            builder.setPositiveButton("Manage Cards") { dialog, _ ->
                dialog.dismiss()
                showManageSavedCardsDialog(savedCards)
            }

            builder.show()
        }
    }

    private fun showManageSavedCardsDialog(savedCards: List<SavedCreditCard>) {
        val cardDescriptions = savedCards.map { card ->
            "${card.nickname}\n${card.getMaskedCardNumber()} - ${card.getFormattedExpiry()}"
        }.toTypedArray()

        AlertDialog.Builder(this, R.style.CustomAlertDialog)
            .setTitle("Manage Saved Cards")
            .setItems(cardDescriptions) { dialog, which ->
                val selectedCard = savedCards[which]
                showCardOptionsDialog(selectedCard)
                dialog.dismiss()
            }
            .setNegativeButton("Back") { dialog, _ ->
                dialog.dismiss()
                showSavedCardsDialog() // Go back to card selection
            }
            .show()
    }

    private fun showCardOptionsDialog(card: SavedCreditCard) {
        AlertDialog.Builder(this, R.style.CustomAlertDialog)
            .setTitle("Card Options")
            .setMessage("${card.nickname}\n${card.getMaskedCardNumber()}")
            .setPositiveButton("Use This Card") { dialog, _ ->
                processSavedCardPayment(card)
                dialog.dismiss()
            }
            .setNeutralButton("Delete Card") { dialog, _ ->
                showDeleteCardConfirmation(card)
                dialog.dismiss()
            }
            .setNegativeButton("Back") { dialog, _ ->
                dialog.dismiss()
                showSavedCardsDialog()
            }
            .show()
    }

    private fun showDeleteCardConfirmation(card: SavedCreditCard) {
        AlertDialog.Builder(this, R.style.CustomAlertDialog)
            .setTitle("Delete Card")
            .setMessage("Are you sure you want to delete this saved card?\n\n${card.nickname}\n${card.getMaskedCardNumber()}")
            .setPositiveButton("Delete") { dialog, _ ->
                deleteSavedCard(card.id) { success ->
                    if (success) {
                        Toast.makeText(this, "Card deleted successfully", Toast.LENGTH_SHORT).show()
                        showSavedCardsDialog() // Refresh the list
                    } else {
                        Toast.makeText(this, "Failed to delete card", Toast.LENGTH_SHORT).show()
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                showSavedCardsDialog()
            }
            .show()
    }

    private fun processSavedCardPayment(savedCard: SavedCreditCard) {
        Log.d(TAG, "Processing payment with saved card: ${savedCard.nickname}")

        // Show loading
        val loadingDialog = AlertDialog.Builder(this, R.style.CustomAlertDialog)
            .setTitle("Processing Payment")
            .setMessage("Please wait...")
            .setCancelable(false)
            .create()
        loadingDialog.show()

        // Validate with Firestore using saved card details
        validateSavedCardPayment(savedCard, loadingDialog)
    }

    private fun validateSavedCardPayment(savedCard: SavedCreditCard, loadingDialog: AlertDialog) {
        Log.d(TAG, "Validating saved card payment...")

        // Add timeout
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            loadingDialog.dismiss()
            showError("Connection timeout. Please try again.")
        }
        handler.postDelayed(timeoutRunnable, 10000)

        // Query Firestore for matching card
        firestore.collection("credit-cards")
            .whereEqualTo("card_number", savedCard.cardNumber.toLong())
            .whereEqualTo("cvv", savedCard.cvv.toInt())
            .whereEqualTo("expiration_month", savedCard.expiryMonth)
            .whereEqualTo("expiration_year", savedCard.expiryYear)
            .get()
            .addOnSuccessListener { documents ->
                handler.removeCallbacks(timeoutRunnable)
                loadingDialog.dismiss()

                if (documents.isEmpty) {
                    showError("Card validation failed. Please try again or use a different card.")
                } else {
                    val cardDoc = documents.first()
                    val balance = (cardDoc.data["balance"] as? Number)?.toDouble() ?: 0.0

                    if (balance >= ticketPrice) {
                        processFirestorePayment(cardDoc.id, balance, null) // null dialog since we're not using the manual entry dialog
                    } else {
                        showError("Insufficient funds. Balance: €${"%.2f".format(balance)}")
                    }
                }
            }
            .addOnFailureListener { error ->
                handler.removeCallbacks(timeoutRunnable)
                loadingDialog.dismiss()
                showError("Payment validation failed: ${error.message}")
            }
    }
}