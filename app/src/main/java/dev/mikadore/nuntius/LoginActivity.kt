package dev.mikadore.nuntius

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dagger.hilt.android.AndroidEntryPoint
import dev.mikadore.nuntius.repos.AuthRepository
import dev.mikadore.nuntius.ui.theme.NuntiusTheme
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LoginActivity : ComponentActivity() {

    @Inject
    lateinit var authRepository: AuthRepository

    private lateinit var barcodeScanner: GmsBarcodeScanner
    private var errorMessage by mutableStateOf<String?>(null)

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
            }
        }
    }

    private fun scanLoginCode() {
        errorMessage = null
        barcodeScanner.startScan()
            .addOnSuccessListener { barcode ->
                val apiKey = barcode.rawValue?.trim()
                if (apiKey.isNullOrEmpty()) {
                    errorMessage = "The QR code did not contain a login key."
                    return@addOnSuccessListener
                }

                lifecycleScope.launch {
                    authRepository.saveApiKey(apiKey)
                    startActivity(Intent(this@LoginActivity, HomeActivity::class.java))
                    finish()
                }
            }
            .addOnFailureListener {
                errorMessage = "Could not scan the login QR code. Please try again."
            }
    }
}
