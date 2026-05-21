package site.sexyminup.p2pfileshare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import site.sexyminup.p2pfileshare.data.SettingsRepository
import site.sexyminup.p2pfileshare.transfer.formatBytes
import site.sexyminup.p2pfileshare.transfer.formatEta
import site.sexyminup.p2pfileshare.transfer.formatSpeed

private enum class Tab { Send, Receive, Settings }

@Composable
fun P2PFileShareScreen(
    state: TransferUiState,
    onPickSendFile: () -> Unit,
    onCreateRoom: () -> Unit,
    onJoinRoom: () -> Unit,
    onCodeChange: (String) -> Unit,
    onServerUrlChange: (String) -> Unit,
    onSaveServerUrl: () -> Unit,
    onRequestSaveLocation: () -> Unit,
    onCancelTransfer: () -> Unit,
) {
    var tab by remember { mutableStateOf(Tab.Send) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.Send,
                    onClick = { tab = Tab.Send },
                    icon = { Icon(Icons.Default.Send, contentDescription = null) },
                    label = { Text("보내기") },
                )
                NavigationBarItem(
                    selected = tab == Tab.Receive,
                    onClick = { tab = Tab.Receive },
                    icon = { Icon(Icons.Default.Download, contentDescription = null) },
                    label = { Text("받기") },
                )
                NavigationBarItem(
                    selected = tab == Tab.Settings,
                    onClick = { tab = Tab.Settings },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("설정") },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("sand honey where", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("파일은 서버에 저장되지 않고 WebRTC DataChannel로만 전송됩니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (tab) {
                Tab.Send -> SendSection(state, onPickSendFile, onCreateRoom)
                Tab.Receive -> ReceiveSection(state, onCodeChange, onJoinRoom, onRequestSaveLocation)
                Tab.Settings -> SettingsSection(state, onServerUrlChange, onSaveServerUrl)
            }
            TransferStatusCard(state, onCancelTransfer)
        }
    }
}

@Composable
private fun SendSection(
    state: TransferUiState,
    onPickSendFile: () -> Unit,
    onCreateRoom: () -> Unit,
) {
    SectionCard {
        Text("파일 보내기", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Button(onClick = onPickSendFile, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Default.UploadFile, contentDescription = null)
            Spacer(Modifier.padding(4.dp))
            Text("보낼 파일 선택")
        }
        if (state.selectedFileName != null) {
            KeyValue("파일", state.selectedFileName)
            KeyValue("크기", formatBytes(state.selectedFileSize))
            KeyValue("형식", state.selectedFileMimeType)
            Button(onClick = onCreateRoom, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("6자리 코드 만들기")
            }
        }
        if (state.roomCode != null) {
            Text(
                text = state.roomCode,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("상대 기기에서 이 코드를 입력하세요.", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ReceiveSection(
    state: TransferUiState,
    onCodeChange: (String) -> Unit,
    onJoinRoom: () -> Unit,
    onRequestSaveLocation: () -> Unit,
) {
    SectionCard {
        Text("파일 받기", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = state.codeInput,
            onValueChange = onCodeChange,
            label = { Text("6자리 코드") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onJoinRoom, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("코드 조회")
        }
        if (state.selectedFileName != null && state.roomCode == null) {
            KeyValue("파일", state.selectedFileName)
            KeyValue("크기", formatBytes(state.selectedFileSize))
            KeyValue("형식", state.selectedFileMimeType)
            Button(onClick = onRequestSaveLocation, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.padding(4.dp))
                Text("저장 위치 선택 후 받기")
            }
        }
    }
}

@Composable
private fun SettingsSection(
    state: TransferUiState,
    onServerUrlChange: (String) -> Unit,
    onSaveServerUrl: () -> Unit,
) {
    SectionCard {
        Text("설정", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = state.editableServerUrl,
            onValueChange = onServerUrlChange,
            label = { Text("서버 URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onSaveServerUrl, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("서버 URL 저장")
        }
        Text("기본값: ${SettingsRepository.DEFAULT_SERVER_URL}")
        Text("보조 예시: ${SettingsRepository.SECONDARY_SERVER_URL}")
        Divider()
        Text("백그라운드로 전환하면 Android 정책에 따라 전송이 끊길 수 있습니다. Foreground Service 전송은 Phase 2 TODO입니다.")
    }
}

@Composable
private fun TransferStatusCard(state: TransferUiState, onCancelTransfer: () -> Unit) {
    SectionCard {
        Text("전송 상태", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        KeyValue("상태", state.status)
        KeyValue("연결", state.connectionState)
        KeyValue("경로", state.pathStatus)
        if (state.totalBytes > 0) {
            val progress = (state.progressBytes.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            KeyValue("진행률", "%.1f%%".format(progress * 100))
            KeyValue("전송량", "${formatBytes(state.progressBytes)} / ${formatBytes(state.totalBytes)}")
            KeyValue("속도", formatSpeed(state.speedBytesPerSecond))
            KeyValue("남은 시간", formatEta(state.etaSeconds))
        }
        if (state.error != null) {
            Text(state.error, color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(onClick = onCancelTransfer, modifier = Modifier.fillMaxWidth()) {
            Text("초기화")
        }
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}
