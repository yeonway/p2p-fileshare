package site.sexyminup.p2pfileshare

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import site.sexyminup.p2pfileshare.ui.P2PFileShareScreen
import site.sexyminup.p2pfileshare.ui.P2PFileShareViewModel
import site.sexyminup.p2pfileshare.ui.theme.P2PFileShareTheme

class MainActivity : ComponentActivity() {
    private val viewModel: P2PFileShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)
        setContent {
            P2PFileShareTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    val notificationPermission = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission(),
                        onResult = {},
                    )
                    val batteryOptimizationPermission = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.StartActivityForResult(),
                        onResult = {},
                    )
                    val openDocument = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument(),
                        onResult = { uri: Uri? ->
                            uri?.let {
                                persistUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                viewModel.selectSendFile(it)
                            }
                        },
                    )
                    val createDocument = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.CreateDocument(state.receiveMimeType),
                        onResult = { uri: Uri? ->
                            uri?.let {
                                persistUriPermission(it, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                viewModel.startReceiving(it)
                            }
                        },
                    )

                    LaunchedEffect(Unit) {
                        if (
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (!isIgnoringBatteryOptimizations()) {
                            val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:$packageName"))
                            runCatching { batteryOptimizationPermission.launch(requestIntent) }
                                .recoverCatching {
                                    batteryOptimizationPermission.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                }
                        }
                    }

                    LaunchedEffect(state.pendingSaveFileName) {
                        val fileName = state.pendingSaveFileName
                        if (fileName != null) {
                            viewModel.consumeSavePickerRequest()
                            createDocument.launch(fileName)
                        }
                    }

                    P2PFileShareScreen(
                        state = state,
                        onPickSendFile = { openDocument.launch(arrayOf("*/*")) },
                        onCreateRoom = viewModel::createSendRoom,
                        onJoinRoom = viewModel::joinReceiveRoom,
                        onCodeChange = viewModel::updateCode,
                        onServerUrlChange = viewModel::updateServerUrl,
                        onSaveServerUrl = viewModel::saveServerUrl,
                        onRequestSaveLocation = viewModel::requestSaveLocation,
                        onCancelTransfer = viewModel::resetTransfer,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val code = intent.data?.extractReceiveCode() ?: return
        viewModel.applyReceiveCode(code)
    }

    private fun Uri.extractReceiveCode(): String? {
        val code = getQueryParameter("code")?.filter(Char::isDigit)?.take(6)
        if (code?.length != 6) return null
        val isCustomScheme = scheme == "sendhoney" && host == "receive"
        val isWebReceiveLink = scheme == "https" &&
            host in setOf("files.dcout.site", "files.sexyminup.site") &&
            path == "/receive"
        return if (isCustomScheme || isWebReceiveLink) code else null
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun persistUriPermission(uri: Uri, modeFlags: Int) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, modeFlags)
        }
    }
}
