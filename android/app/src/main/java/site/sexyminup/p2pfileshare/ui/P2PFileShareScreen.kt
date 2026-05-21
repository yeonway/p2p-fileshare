package site.sexyminup.p2pfileshare.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import site.sexyminup.p2pfileshare.R
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
    LaunchedEffect(state.codeInput) {
        if (state.codeInput.length == 6 && state.roomCode == null) {
            tab = Tab.Receive
        }
    }
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFFFFFAF0)) {
                NavigationBarItem(
                    selected = tab == Tab.Send,
                    onClick = { tab = Tab.Send },
                    icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF17110A), Color(0xFFFBF6EB), Color(0xFFF6EAD2)),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HeroHeader()
                when (tab) {
                    Tab.Send -> SendSection(state, onPickSendFile, onCreateRoom)
                    Tab.Receive -> ReceiveSection(state, onCodeChange, onJoinRoom, onRequestSaveLocation)
                    Tab.Settings -> SettingsSection(state, onServerUrlChange, onSaveServerUrl)
                }
                TransferStatusCard(state, onCancelTransfer)
            }
        }
    }
}

@Composable
private fun HeroHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(250.dp)
            .clip(RoundedCornerShape(24.dp)),
    ) {
        Image(
            painter = painterResource(R.drawable.honey_transfer_hero),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color(0xF214100C), Color(0xAA14100C), Color(0x2214100C)),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(22.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HoneyLogo()
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "send honey where",
                    color = Color(0xFFFFF8E3),
                    fontWeight = FontWeight.Black,
                    fontSize = 19.sp,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Private peer-to-peer transfer",
                    color = Color(0xFFFFD86B),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
                Text(
                    text = "서버에 남기지 않고\n기기 사이로 직접 전송",
                    color = Color(0xFFFFF8E3),
                    fontWeight = FontWeight.Black,
                    fontSize = 30.sp,
                    lineHeight = 34.sp,
                )
                Text(
                    text = "전송 중에는 백그라운드 알림으로 연결을 유지합니다.",
                    color = Color(0xFFF9E9BC),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun HoneyLogo() {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFFFD86B), Color(0xFFF0A51F), Color(0xFF7B3F09)))),
        contentAlignment = Alignment.Center,
    ) {
        Text("h", color = Color(0xFF241306), fontWeight = FontWeight.Black, fontSize = 24.sp)
    }
}

@Composable
private fun SendSection(
    state: TransferUiState,
    onPickSendFile: () -> Unit,
    onCreateRoom: () -> Unit,
) {
    SectionCard(title = "파일 보내기", label = "Send") {
        Text("전송할 파일을 선택하고 6자리 코드를 공유하세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onPickSendFile, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Default.UploadFile, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("보낼 파일 선택")
        }
        if (state.selectedFileName != null) {
            KeyValue("파일", state.selectedFileName)
            KeyValue("크기", formatBytes(state.selectedFileSize))
            KeyValue("형식", state.selectedFileMimeType)
            Button(onClick = onCreateRoom, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("6자리 코드 만들기")
            }
        }
        if (state.roomCode != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFFFFF3CF),
            ) {
                Column(modifier = Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("상대 기기에 입력할 코드", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = state.roomCode,
                        textAlign = TextAlign.Center,
                        fontSize = 46.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFFA85B0D),
                    )
                }
            }
            if (state.qrPayload != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFFE8F6F3),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("QR로 앱 열기", fontWeight = FontWeight.Black)
                        Image(
                            bitmap = remember(state.qrPayload) { createQrImageBitmap(state.qrPayload) },
                            contentDescription = "받기 앱 열기 QR",
                            modifier = Modifier
                                .size(210.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White)
                                .padding(10.dp),
                        )
                        Text(
                            "Android 앱 설치 후 QR을 찍으면 받기 화면에 코드가 자동 입력됩니다.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        state.shareUrl?.let { KeyValue("웹 링크", it) }
                    }
                }
            }
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
    SectionCard(title = "파일 받기", label = "Receive") {
        Text("전달받은 코드를 입력하고 저장 위치를 선택하세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = state.codeInput,
            onValueChange = onCodeChange,
            label = { Text("6자리 코드") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onJoinRoom, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Default.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("파일 정보 확인")
        }
        if (state.selectedFileName != null && state.roomCode == null) {
            KeyValue("파일", state.selectedFileName)
            KeyValue("크기", formatBytes(state.selectedFileSize))
            KeyValue("형식", state.selectedFileMimeType)
            Button(onClick = onRequestSaveLocation, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
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
    SectionCard(title = "설정", label = "Server") {
        Text("서버 도메인은 보안을 위해 고정되어 있습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFFE8F6F3),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("고정 서버", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                Text(SettingsRepository.DEFAULT_SERVER_HOST, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text(state.serverUrl, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(
            onClick = onSaveServerUrl,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E)),
        ) {
            Text("고정값 다시 적용")
        }
        InfoPill("백그라운드", "전송 중 foreground service 알림으로 앱이 백그라운드에 있어도 연결을 유지합니다.")
        InfoPill("보안", "임의 서버 입력을 막고 HTTPS 운영 도메인만 사용합니다.")
        InfoPill("파일", "파일 본문은 서버에 업로드하지 않고 WebRTC DataChannel로만 전송합니다.")
    }
}

@Composable
private fun TransferStatusCard(state: TransferUiState, onCancelTransfer: () -> Unit) {
    SectionCard(title = "전송 상태", label = "Status") {
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
            val errorText = state.errorCode?.let { "[$it] ${state.error}" } ?: state.error
            Text(errorText, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = onCancelTransfer, modifier = Modifier.fillMaxWidth()) {
            Text("초기화")
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    label: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.94f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Surface(shape = RoundedCornerShape(999.dp), color = Color(0xFFFFF3CF)) {
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        color = Color(0xFFA85B0D),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun InfoPill(title: String, body: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFFFFAF0),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Black)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}
