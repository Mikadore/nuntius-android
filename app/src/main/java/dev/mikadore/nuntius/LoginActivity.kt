package dev.mikadore.nuntius

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.mikadore.nuntius.lib.PairingRequest
import dev.mikadore.nuntius.ui.theme.NuntiusTheme

class LoginActivity : ComponentActivity() {

    private lateinit var barcodeScanner: GmsBarcodeScanner
    private var errorMessage by mutableStateOf<String?>(null)
    private var pairingRequest by mutableStateOf<PairingRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        barcodeScanner = GmsBarcodeScanning.getClient(this, options)

        setContent {
            NuntiusTheme {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Button(onClick = ::scanLoginCode) {
                        Text("Scan login QR code")
                    }
                    errorMessage?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                }

                pairingRequest?.let { request ->
                    AlertDialog(
                        onDismissRequest = { pairingRequest = null },
                        title = { Text("Pairing request") },
                        text = {
                            Column {
                                Text("Server hostname: ${request.serverHostname}")
                                Text("Server claim path: ${request.serverClaimPath}")
                                Text("Session ID: ${request.sessionId}")
                                Text("Token: ${request.token}")
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { pairingRequest = null }) {
                                Text("Close")
                            }
                        },
                    )
                }
            }
        }
    }

    private fun scanLoginCode() {
        errorMessage = null
        barcodeScanner.startScan()
            .addOnSuccessListener { barcode ->
                val pairingUriValue = barcode.rawValue?.trim()
                if (pairingUriValue.isNullOrEmpty()) {
                    errorMessage = "The QR code did not contain a pairing URI."
                    return@addOnSuccessListener
                }

                try {
                    pairingRequest = PairingRequest.parse(pairingUriValue)
                } catch (_: Exception) {
                    errorMessage = "The QR code did not contain a valid pairing URI."
                }
            }
            .addOnFailureListener {
                errorMessage = "Could not scan the login QR code. Please try again."
            }
    }
}
