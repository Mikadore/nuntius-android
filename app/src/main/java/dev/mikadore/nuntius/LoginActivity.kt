package dev.mikadore.nuntius

import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dagger.hilt.android.AndroidEntryPoint
import dev.mikadore.nuntius.lib.PairingException
import dev.mikadore.nuntius.lib.PairingFlow
import dev.mikadore.nuntius.lib.PairingCredential
import dev.mikadore.nuntius.lib.PairingRequest
import dev.mikadore.nuntius.lib.PairingSessionInfo
import dev.mikadore.nuntius.repos.AuthRepository
import dev.mikadore.nuntius.ui.theme.NuntiusTheme
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

@AndroidEntryPoint
class LoginActivity : ComponentActivity() {

    @Inject
    lateinit var authRepository: AuthRepository

    private lateinit var barcodeScanner: GmsBarcodeScanner
    private val pairingState = MutableStateFlow<PairingDialogState>(PairingDialogState.Idle)
    private var pairingJob: Job? = null
    private var activeFlow: PairingFlow? = null
    private var scanError by mutableStateOf<String?>(null)
    private var isOpeningHome = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        barcodeScanner = GmsBarcodeScanning.getClient(this, options)

        setContent {
            NuntiusTheme {
                val dialogState by pairingState.collectAsStateWithLifecycle()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Button(
                        onClick = ::scanLoginCode,
                        enabled = dialogState == PairingDialogState.Idle,
                    ) {
                        Text("Scan Nuntius pairing code")
                    }
                    scanError?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }

                PairingDialog(
                    state = dialogState,
                    onDismiss = ::dismissPairing,
                    onContinue = ::loadPairingSession,
                    onConfirmCode = ::claimPairingSession,
                    onRetry = ::retryPairing,
                    onComplete = ::openHome,
                )
            }
        }
    }

    override fun onDestroy() {
        pairingJob?.cancel()
        super.onDestroy()
    }

    private fun scanLoginCode() {
        scanError = null
        barcodeScanner.startScan()
            .addOnSuccessListener { barcode ->
                val rawPairingUri = barcode.rawValue?.trim()
                if (rawPairingUri.isNullOrEmpty()) {
                    scanError = "The QR code did not contain a pairing URI."
                    return@addOnSuccessListener
                }

                val request = try {
                    PairingRequest.parse(rawPairingUri)
                } catch (failure: IllegalArgumentException) {
                    scanError = failure.message ?: "The QR code did not contain a valid pairing URI."
                    return@addOnSuccessListener
                }
                probeServer(request)
            }
            .addOnFailureListener {
                scanError = "Could not scan the pairing code. Please try again."
            }
    }

    private fun probeServer(request: PairingRequest) {
        pairingJob?.cancel()
        val flow = PairingFlow(request)
        activeFlow = flow
        pairingState.value = PairingDialogState.Probing(request)
        pairingJob = lifecycleScope.launch {
            try {
                flow.probe()
                pairingState.value = PairingDialogState.Ready(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                pairingState.value = PairingDialogState.Failed(
                    request = request,
                    message = failure.toPairingMessage(),
                    retry = PairingRetry.PROBE,
                )
            }
        }
    }

    private fun loadPairingSession(request: PairingRequest) {
        pairingJob?.cancel()
        val flow = PairingFlow(request)
        activeFlow = flow
        pairingState.value = PairingDialogState.LoadingSession(request, "Checking the pairing session…")
        pairingJob = lifecycleScope.launch {
            try {
                val info = flow.prepareClaim()
                pairingState.value = PairingDialogState.VerifyCode(request, info)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                pairingState.value = PairingDialogState.Failed(
                    request = request,
                    message = failure.toPairingMessage(),
                    retry = PairingRetry.SESSION,
                )
            }
        }
    }

    private fun claimPairingSession(request: PairingRequest, info: PairingSessionInfo) {
        pairingJob?.cancel()
        val flow = activeFlow ?: PairingFlow(request).also { activeFlow = it }
        pairingState.value = PairingDialogState.Claiming(
            request = request,
            info = info,
            progress = "Sending the pairing request…",
        )
        pairingJob = lifecycleScope.launch {
            try {
                val deviceName = Build.MODEL
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.take(80)
                    ?: "Android device"
                val credential: PairingCredential = flow.claimAndWaitForApproval(info, deviceName) { progress ->
                    pairingState.value = PairingDialogState.Claiming(request, info, progress)
                }
                withContext(Dispatchers.IO) {
                    authRepository.saveCredentials(
                        serverUrl = request.serverBaseUrl,
                        apiKey = credential.accessToken,
                        credentialId = credential.credentialId.toString(),
                    )
                }
                activeFlow = null
                pairingState.value = PairingDialogState.Complete
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                pairingState.value = PairingDialogState.Failed(
                    request = request,
                    message = failure.toPairingMessage(),
                    retry = PairingRetry.SESSION,
                )
            }
        }
    }

    private fun retryPairing(state: PairingDialogState.Failed) {
        when (state.retry) {
            PairingRetry.PROBE -> probeServer(state.request)
            PairingRetry.SESSION -> loadPairingSession(state.request)
        }
    }

    private fun dismissPairing() {
        val wasClaiming = pairingState.value is PairingDialogState.Claiming
        val flow = activeFlow
        pairingJob?.cancel()
        pairingJob = null
        activeFlow = null
        pairingState.value = PairingDialogState.Idle

        if (wasClaiming && flow != null) {
            lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { flow.cancel() }
                }
            }
        }
    }

    private fun openHome() {
        if (isOpeningHome) return
        isOpeningHome = true
        startActivity(android.content.Intent(this, HomeActivity::class.java))
        finish()
    }

    private fun Exception.toPairingMessage(): String = when (this) {
        is PairingException -> message ?: "Pairing could not be completed."
        is SocketTimeoutException -> "The server took too long to respond. Check the connection and try again."
        is IOException -> "Could not connect to the server. Check the connection and try again."
        else -> "Pairing could not be completed. Please try again."
    }
}

@androidx.compose.runtime.Composable
private fun PairingDialog(
    state: PairingDialogState,
    onDismiss: () -> Unit,
    onContinue: (PairingRequest) -> Unit,
    onConfirmCode: (PairingRequest, PairingSessionInfo) -> Unit,
    onRetry: (PairingDialogState.Failed) -> Unit,
    onComplete: () -> Unit,
) {
    when (state) {
        PairingDialogState.Idle -> Unit

        is PairingDialogState.Probing -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Checking Nuntius server") },
            text = {
                ProgressMessage("Connecting securely to https://${state.request.serverDisplayAddress}…")
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )

        is PairingDialogState.Ready -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Server is reachable") },
            text = { Text("Continue to pair this Android device with https://${state.request.serverDisplayAddress}?") },
            confirmButton = {
                TextButton(onClick = { onContinue(state.request) }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )

        is PairingDialogState.LoadingSession -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Preparing pairing") },
            text = { ProgressMessage(state.message) },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )

        is PairingDialogState.VerifyCode -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Confirm pairing code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This app generated the code. Send the pairing request, then check it in the web UI before approving.")
                    state.info.deviceName?.let { Text("Device: $it") }
                    Text(
                        text = state.info.confirmationCode,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onConfirmCode(state.request, state.info) }) {
                    Text("Send pairing request")
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )

        is PairingDialogState.Claiming -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Approve this device in the web UI") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(state.progress)
                    Text("Confirmation code")
                    Text(
                        text = state.info.confirmationCode,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp,
                    )
                    Text("The web UI will show this code. Approve or deny the request there.")
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )

        is PairingDialogState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Pairing failed") },
            text = { Text(state.message, color = MaterialTheme.colorScheme.error) },
            confirmButton = {
                TextButton(onClick = { onRetry(state) }) { Text("Try again") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )

        PairingDialogState.Complete -> AlertDialog(
            onDismissRequest = onComplete,
            title = { Text("Paired successfully") },
            text = { Text("Your server credentials have been saved securely.") },
            confirmButton = {
                TextButton(onClick = onComplete) { Text("Open Nuntius") }
            },
        )
    }
}

@androidx.compose.runtime.Composable
private fun ProgressMessage(message: String) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(message)
    }
}

private sealed interface PairingDialogState {
    data object Idle : PairingDialogState
    data class Probing(val request: PairingRequest) : PairingDialogState
    data class Ready(val request: PairingRequest) : PairingDialogState
    data class LoadingSession(val request: PairingRequest, val message: String) : PairingDialogState
    data class VerifyCode(val request: PairingRequest, val info: PairingSessionInfo) : PairingDialogState
    data class Claiming(
        val request: PairingRequest,
        val info: PairingSessionInfo,
        val progress: String,
    ) : PairingDialogState
    data class Failed(
        val request: PairingRequest,
        val message: String,
        val retry: PairingRetry,
    ) : PairingDialogState
    data object Complete : PairingDialogState
}

private enum class PairingRetry { PROBE, SESSION }
