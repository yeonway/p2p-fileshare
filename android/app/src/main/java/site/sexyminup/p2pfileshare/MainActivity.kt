package site.sexyminup.p2pfileshare

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import site.sexyminup.p2pfileshare.ui.P2PFileShareScreen
import site.sexyminup.p2pfileshare.ui.P2PFileShareViewModel
import site.sexyminup.p2pfileshare.ui.theme.P2PFileShareTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            P2PFileShareTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val viewModel: P2PFileShareViewModel = viewModel()
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    val openDocument = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument(),
                        onResult = { uri: Uri? -> uri?.let(viewModel::selectSendFile) },
                    )
                    val createDocument = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.CreateDocument(state.receiveMimeType),
                        onResult = { uri: Uri? -> uri?.let(viewModel::startReceiving) },
                    )

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
}
