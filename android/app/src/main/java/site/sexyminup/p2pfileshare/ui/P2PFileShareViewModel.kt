package site.sexyminup.p2pfileshare.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.webrtc.DataChannel
import site.sexyminup.p2pfileshare.P2PTransferForegroundService
import site.sexyminup.p2pfileshare.data.ApiClient
import site.sexyminup.p2pfileshare.data.ConfigResponse
import site.sexyminup.p2pfileshare.data.RoomCreateResponse
import site.sexyminup.p2pfileshare.data.RoomJoinResponse
import site.sexyminup.p2pfileshare.data.SettingsRepository
import site.sexyminup.p2pfileshare.signaling.SignalingClient
import site.sexyminup.p2pfileshare.signaling.WsMessage
import site.sexyminup.p2pfileshare.transfer.AckMessage
import site.sexyminup.p2pfileshare.transfer.ErrorMessage
import site.sexyminup.p2pfileshare.transfer.FileMetadata
import site.sexyminup.p2pfileshare.transfer.ManifestMessage
import site.sexyminup.p2pfileshare.transfer.ReceiverCompleteMessage
import site.sexyminup.p2pfileshare.transfer.SenderFinishedMessage
import site.sexyminup.p2pfileshare.transfer.TransferErrorCodes
import site.sexyminup.p2pfileshare.transfer.chunkCount
import site.sexyminup.p2pfileshare.transfer.encodeAck
import site.sexyminup.p2pfileshare.transfer.encodeError
import site.sexyminup.p2pfileshare.transfer.encodeManifest
import site.sexyminup.p2pfileshare.transfer.encodeReceiverComplete
import site.sexyminup.p2pfileshare.transfer.encodeSenderFinished
import site.sexyminup.p2pfileshare.transfer.messageType
import site.sexyminup.p2pfileshare.transfer.queryFileMetadata
import site.sexyminup.p2pfileshare.transfer.resolveDataChannelChunkSize
import site.sexyminup.p2pfileshare.webrtc.WebRtcManager
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.max

class P2PFileShareViewModel(application: Application) : AndroidViewModel(application) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val httpClient = OkHttpClient.Builder().build()
    private val apiClient = ApiClient(httpClient, json)
    private val signalingClient = SignalingClient(httpClient, json)
    private val settingsRepository = SettingsRepository(application)
    private val contentResolver = application.contentResolver
    private val _uiState = MutableStateFlow(TransferUiState())
    val uiState: StateFlow<TransferUiState> = _uiState.asStateFlow()

    private var config: ConfigResponse? = null
    private var room: RoomCreateResponse? = null
    private var receiveRoom: RoomJoinResponse? = null
    private var webRtcManager: WebRtcManager? = null
    private var controlChannel: DataChannel? = null
    private var fileChannel: DataChannel? = null
    private var sendFile: FileMetadata? = null
    private var transferId: String? = null
    private var sendingStarted = false
    private var senderFinished = false
    private var receiverComplete = false
    private var bytesSent = 0L
    private var ackedBytes = 0L
    private var chunksSent = 0L
    private var manifest: ManifestMessage? = null
    private var outputStream: OutputStream? = null
    private var receiveQueue: Channel<ByteArray>? = null
    private var receiveWriterJob: Job? = null
    private var bytesReceived = 0L
    private var chunkIndex = -1L
    private var receiverSenderFinished = false
    private var completed = false
    private var failed = false
    private var lastAckAt = 0L
    private var lastProgressAt = 0L
    private var transferStartedAt = 0L
    private var timeoutJob: Job? = null
    private var foregroundActive = false
    private var lastForegroundUpdateAt = 0L
    private var lastForegroundStatus = ""
    private val receiveMutex = Mutex()

    init {
        viewModelScope.launch {
            settingsRepository.serverUrl.collect { fixedUrl ->
                _uiState.update { it.copy(serverUrl = fixedUrl, editableServerUrl = fixedUrl) }
            }
        }
    }

    fun updateServerUrl(url: String) {
        _uiState.update {
            it.copy(
                serverUrl = SettingsRepository.DEFAULT_SERVER_URL,
                editableServerUrl = SettingsRepository.DEFAULT_SERVER_URL,
            )
        }
    }

    fun saveServerUrl() {
        viewModelScope.launch {
            settingsRepository.saveServerUrl()
            _uiState.update {
                it.copy(
                    serverUrl = SettingsRepository.DEFAULT_SERVER_URL,
                    editableServerUrl = SettingsRepository.DEFAULT_SERVER_URL,
                    error = null,
                    status = "고정 서버 적용됨",
                )
            }
        }
    }

    fun selectSendFile(uri: Uri) {
        runCatching { queryFileMetadata(contentResolver, uri) }
            .onSuccess { file ->
                sendFile = file
                _uiState.update {
                    it.copy(
                        selectedFileName = file.name,
                        selectedFileSize = file.size,
                        selectedFileMimeType = file.mimeType,
                        totalBytes = file.size,
                        progressBytes = 0L,
                        error = null,
                        status = "파일 선택됨",
                    )
                }
            }
            .onFailure { setError(it.message ?: "파일 정보를 읽지 못했습니다.") }
    }

    fun updateCode(code: String) {
        _uiState.update { it.copy(codeInput = code.filter(Char::isDigit).take(6)) }
    }

    fun applyReceiveCode(code: String) {
        val normalized = code.filter(Char::isDigit).take(6)
        if (normalized.length == 6) {
            _uiState.update {
                it.copy(
                    codeInput = normalized,
                    roomCode = null,
                    error = null,
                    errorCode = null,
                    status = "QR 코드 입력됨",
                )
            }
        }
    }

    fun createSendRoom() {
        val file = sendFile ?: run {
            setError("보낼 파일을 먼저 선택하세요.")
            return
        }
        startFreshTransfer("서버 연결 중")
        receiveRoom = null
        viewModelScope.launch {
            runCatching {
                val serverUrl = uiState.value.serverUrl
                val loadedConfig = apiClient.getConfig(serverUrl)
                config = loadedConfig
                setStatus("방 생성 중")
                val created = apiClient.createRoom(serverUrl, file.name, file.size, file.mimeType)
                room = created
                transferId = UUID.randomUUID().toString()
                _uiState.update {
                    val shareUrl = buildReceiveUrl(serverUrl, created.code)
                    it.copy(
                        roomCode = created.code,
                        shareUrl = shareUrl,
                        qrPayload = buildAndroidIntentUrl(created.code, shareUrl),
                        expiresAt = created.expiresAt,
                        totalBytes = file.size,
                    )
                }
                setStatus("상대 접속 대기 중")
                connectSignaling(created.roomId, "sender")
            }.onFailure { failTransfer(it.message ?: "방 생성 실패") }
        }
    }

    fun joinReceiveRoom() {
        val code = uiState.value.codeInput
        if (!Regex("^\\d{6}$").matches(code)) {
            setError("6자리 코드를 입력하세요.")
            return
        }
        startFreshTransfer("서버 연결 중")
        room = null
        viewModelScope.launch {
            runCatching {
                val serverUrl = uiState.value.serverUrl
                val loadedConfig = apiClient.getConfig(serverUrl)
                config = loadedConfig
                val joined = apiClient.joinRoom(serverUrl, code)
                receiveRoom = joined
                _uiState.update {
                    it.copy(
                        status = "저장 위치 선택 대기 중",
                        selectedFileName = joined.fileName,
                        selectedFileSize = joined.fileSize,
                        selectedFileMimeType = joined.mimeType,
                        receiveMimeType = joined.mimeType.ifBlank { "application/octet-stream" },
                        totalBytes = joined.fileSize,
                    )
                }
                updateForeground()
            }.onFailure { failTransfer(it.message ?: "방 참가 실패") }
        }
    }

    fun requestSaveLocation() {
        val joined = receiveRoom ?: run {
            setError("먼저 6자리 코드로 받을 파일을 조회하세요.")
            return
        }
        _uiState.update { it.copy(pendingSaveFileName = joined.fileName) }
    }

    fun consumeSavePickerRequest() {
        _uiState.update { it.copy(pendingSaveFileName = null) }
    }

    fun startReceiving(uri: Uri) {
        val joined = receiveRoom ?: return
        viewModelScope.launch {
            runCatching {
                outputStream = withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "w")
                } ?: error("저장 파일을 열 수 없습니다.")
                setStatus("상대 접속 대기 중")
                connectSignaling(joined.roomId, "receiver")
            }.onFailure { failTransfer(it.message ?: "수신 준비 실패", TransferErrorCodes.STORAGE_OPEN_FAILED) }
        }
    }

    fun resetTransfer() {
        timeoutJob?.cancel()
        closeReceiveQueue(cancelWriter = true)
        signalingClient.close()
        webRtcManager?.close()
        outputStream?.closeQuietly()
        outputStream = null
        controlChannel = null
        fileChannel = null
        stopForeground()
        _uiState.update {
            TransferUiState(
                serverUrl = SettingsRepository.DEFAULT_SERVER_URL,
                editableServerUrl = SettingsRepository.DEFAULT_SERVER_URL,
                codeInput = it.codeInput,
            )
        }
    }

    private fun connectSignaling(roomId: String, role: String) {
        signalingClient.connect(
            baseUrl = uiState.value.serverUrl,
            roomId = roomId,
            role = role,
            onOpen = {
                sendSignaling(WsMessage(type = if (role == "sender") "sender-ready" else "receiver-ready"))
                setStatus(if (role == "sender") "코드 대기 중" else "WebRTC 연결 중")
            },
            onClosed = {
                if (!completed && !failed && hasActiveTransfer()) {
                    failTransfer("signaling 연결이 전송 중 종료되었습니다.", TransferErrorCodes.SIGNALING_CLOSED)
                } else if (!completed && !failed) {
                    _uiState.update { it.copy(connectionState = "signaling 종료") }
                }
            },
            onFailure = { failTransfer(it.message ?: "signaling 오류", TransferErrorCodes.SIGNALING_ERROR) },
            onMessage = { message -> handleSignalingMessage(role, message) },
        )
    }

    private fun handleSignalingMessage(role: String, message: WsMessage) {
        viewModelScope.launch {
            runCatching {
                when (message.type) {
                    "receiver-ready" -> if (role == "sender") createOffer()
                    "offer" -> if (role == "receiver" && message.sdp != null) acceptOffer(message.sdp)
                    "answer" -> if (role == "sender" && message.sdp != null) {
                        webRtcManager?.setRemoteDescription(message.sdp)
                    }
                    "ice-candidate" -> message.candidate?.let { webRtcManager?.addIceCandidate(it) }
                }
            }.onFailure { failTransfer(it.message ?: "signaling 처리 실패", TransferErrorCodes.SIGNALING_ERROR) }
        }
    }

    private suspend fun createOffer() {
        val loadedConfig = requireNotNull(config)
        val manager = newWebRtcManager()
        manager.createPeerConnection(loadedConfig)
        controlChannel = manager.createDataChannel("control").also(::registerControlChannel)
        fileChannel = manager.createDataChannel("file").also(::registerFileChannel)
        setStatus("WebRTC 연결 중")
        val offer = manager.createOffer()
        sendSignaling(WsMessage(type = "offer", sdp = offer))
    }

    private suspend fun acceptOffer(sdp: site.sexyminup.p2pfileshare.signaling.SessionDescriptionDto) {
        val loadedConfig = requireNotNull(config)
        val manager = newWebRtcManager()
        manager.createPeerConnection(loadedConfig)
        manager.setRemoteDescription(sdp)
        val answer = manager.createAnswer()
        sendSignaling(WsMessage(type = "answer", sdp = answer))
    }

    private fun newWebRtcManager(): WebRtcManager {
        val manager = WebRtcManager(
            context = getApplication(),
            onIceCandidate = { sendSignaling(WsMessage(type = "ice-candidate", candidate = it)) },
            onConnectionState = { state ->
                _uiState.update { it.copy(connectionState = state, status = state.toKoreanConnectionStatus()) }
                updateForeground()
                if (state == "FAILED" || state == "CLOSED") {
                    failTransfer("WebRTC 연결이 전송 중 끊어졌습니다.", TransferErrorCodes.PEER_CONNECTION_FAILED)
                } else if (state == "CONNECTED" || state == "COMPLETED") {
                    _uiState.update { it.copy(pathStatus = "직접/TURN 확인 중") }
                    webRtcManager?.reportConnectionPath { direct, turn ->
                        _uiState.update {
                            it.copy(
                                pathStatus = when {
                                    turn == true -> "TURN 중계 가능성 있음"
                                    direct == true -> "직접 연결됨"
                                    else -> "직접/TURN 확인 불가"
                                },
                            )
                        }
                        sendSignaling(
                            WsMessage(
                                type = "connection-info",
                                role = if (room != null) "sender" else "receiver",
                                directP2p = direct,
                                turnUsed = turn,
                            ),
                        )
                    }
                }
            },
            onDataChannel = { channel ->
                if (channel.label() == "control") {
                    controlChannel = channel.also(::registerControlChannel)
                } else if (channel.label() == "file") {
                    fileChannel = channel.also(::registerFileChannel)
                }
            },
        )
        webRtcManager = manager
        return manager
    }

    private fun registerControlChannel(channel: DataChannel) {
        channel.registerObserver(
            object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit
                override fun onStateChange() {
                    runCatching {
                        if (channel.state() == DataChannel.State.OPEN && room != null) {
                            sendManifest()
                        }
                    }.onFailure {
                        failTransfer(it.message ?: "control DataChannel 상태 처리 실패", TransferErrorCodes.UNKNOWN)
                    }
                }

                override fun onMessage(buffer: DataChannel.Buffer) {
                    runCatching {
                        val data = buffer.data.copyRemainingBytes()
                        handleControlMessage(data.decodeToString())
                    }.onFailure {
                        failTransfer(it.message ?: "control 메시지 수신 실패", TransferErrorCodes.UNKNOWN)
                    }
                }
            },
        )
    }

    private fun registerFileChannel(channel: DataChannel) {
        channel.registerObserver(
            object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit
                override fun onStateChange() {
                    runCatching {
                        if (channel.state() == DataChannel.State.CLOSED) {
                            handleFileChannelClosed()
                        }
                    }.onFailure {
                        failTransfer(it.message ?: "file DataChannel 상태 처리 실패", TransferErrorCodes.DATA_CHANNEL_CLOSED)
                    }
                }
                override fun onMessage(buffer: DataChannel.Buffer) {
                    runCatching {
                        if (!buffer.binary) return
                        enqueueReceivedChunk(buffer.data.copyRemainingBytes())
                    }.onFailure {
                        failTransfer(it.message ?: "file chunk 수신 실패", TransferErrorCodes.UNKNOWN)
                    }
                }
            },
        )
    }

    private fun sendManifest() {
        val file = sendFile ?: return
        val channel = controlChannel ?: return
        val chunkSize = resolveChunkSize()
        val manifest = ManifestMessage(
            transferId = requireNotNull(transferId),
            fileName = file.name,
            fileSize = file.size,
            mimeType = file.mimeType,
            chunkSize = chunkSize,
            chunkCount = chunkCount(file.size, chunkSize),
        )
        if (!channel.safeSendText(json.encodeManifest(manifest))) {
            failTransfer("control DataChannel로 manifest를 보낼 수 없습니다.", TransferErrorCodes.DATA_CHANNEL_CLOSED)
        }
    }

    private fun handleControlMessage(text: String) {
        viewModelScope.launch {
            runCatching {
                when (json.messageType(text)) {
                    "manifest" -> handleManifest(json.decodeFromString<ManifestMessage>(text))
                    "ack" -> handleAck(json.decodeFromString<AckMessage>(text))
                    "sender-finished", "complete" -> handleSenderFinished(json.decodeFromString<SenderFinishedMessage>(text))
                    "receiver-complete" -> handleReceiverComplete(json.decodeFromString<ReceiverCompleteMessage>(text))
                    "error" -> json.decodeFromString<ErrorMessage>(text).let {
                        failTransfer(it.message, it.code.ifBlank { TransferErrorCodes.REMOTE_ERROR })
                    }
                }
            }.onFailure { failTransfer(it.message ?: "control 메시지 처리 실패", TransferErrorCodes.UNKNOWN) }
        }
    }

    private suspend fun handleManifest(message: ManifestMessage) {
        val joined = receiveRoom ?: return
        if (message.fileSize != joined.fileSize) {
            failTransfer("파일 크기 metadata가 일치하지 않습니다.", TransferErrorCodes.METADATA_MISMATCH)
            return
        }
        manifest = message
        bytesReceived = 0L
        chunkIndex = -1L
        receiverSenderFinished = false
        transferStartedAt = System.nanoTime()
        _uiState.update { it.copy(status = "전송 중", totalBytes = message.fileSize, progressBytes = 0L) }
        updateForeground()
        startReceiveWriter(message.fileSize)
        sendSignaling(WsMessage(type = "transfer-started"))
        sendAck(initial = true)
    }

    private suspend fun handleAck(message: AckMessage) {
        ackedBytes = max(ackedBytes, message.receivedBytes)
        updateProgress(ackedBytes, sendFile?.size ?: 0L)
        if (message.receivedBytes == 0L && !sendingStarted) {
            runCatching { sendFileChunks() }
                .onFailure { failTransfer(it.message ?: "DataChannel 전송 실패", TransferErrorCodes.DATA_CHANNEL_TIMEOUT) }
        } else if (senderFinished && !receiverComplete) {
            setStatus("수신 완료 확인 대기 중")
        }
    }

    private fun handleSenderFinished(message: SenderFinishedMessage) {
        receiverSenderFinished = true
        val expected = manifest?.fileSize ?: receiveRoom?.fileSize ?: 0L
        if (message.totalBytes != expected) {
            failTransfer("송신자가 보고한 전체 크기가 원본 파일 크기와 일치하지 않습니다.", TransferErrorCodes.SIZE_MISMATCH)
            return
        }
        if (bytesReceived < expected) {
            setStatus("남은 데이터 대기 중")
            scheduleMissingDataTimeout()
        }
        viewModelScope.launch { maybeFinalizeReceive() }
    }

    private fun handleReceiverComplete(message: ReceiverCompleteMessage) {
        val file = sendFile ?: return
        if (message.totalBytes != file.size) {
            failTransfer("수신 완료 크기가 원본과 일치하지 않습니다.", TransferErrorCodes.SIZE_MISMATCH)
            return
        }
        receiverComplete = true
        completed = true
        updateProgress(file.size, file.size)
        setStatus("완료")
        stopForeground()
    }

    private suspend fun sendFileChunks() {
        val file = sendFile ?: return
        val fileChannel = fileChannel ?: error("file DataChannel이 준비되지 않았습니다.")
        sendingStarted = true
        transferStartedAt = System.nanoTime()
        bytesSent = 0L
        chunksSent = 0L
        ackedBytes = 0L
        _uiState.update { it.copy(status = "전송 중", progressBytes = 0L, totalBytes = file.size) }
        updateForeground()
        sendSignaling(WsMessage(type = "transfer-started"))
        waitForDataChannelOpen(fileChannel)
        val chunkSize = resolveChunkSize()
        withContext(Dispatchers.IO) {
            contentResolver.openInputStream(file.uri).use { input ->
                requireNotNull(input) { "파일을 열 수 없습니다." }
                readAndSend(input, fileChannel, chunkSize)
            }
        }
        setStatus("전송 버퍼 비우는 중")
        waitForDrain(fileChannel, lowWaterBytes(chunkSize), timeoutMs = activeTimeoutMs())
        senderFinished = true
        controlChannel?.safeSendText(
            json.encodeSenderFinished(
                SenderFinishedMessage(
                    transferId = requireNotNull(transferId),
                    totalBytes = file.size,
                    lastChunkIndex = chunksSent - 1,
                ),
            ),
        )
        setStatus("수신 완료 확인 대기 중")
    }

    private suspend fun readAndSend(input: InputStream, channel: DataChannel, chunkSize: Int) {
        val buffer = ByteArray(chunkSize)
        while (true) {
            waitForSendBuffer(channel, chunkSize, timeoutMs = activeTimeoutMs())
            val read = input.read(buffer)
            if (read < 0) break
            val payload = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
            sendBinaryChunk(channel, payload, chunkSize, timeoutMs = activeTimeoutMs())
            bytesSent += read
            chunksSent += 1
            val now = System.currentTimeMillis()
            if (now - lastProgressAt >= 1000L) {
                lastProgressAt = now
                sendSignaling(WsMessage(type = "transfer-progress", bytesSent = bytesSent))
            }
        }
    }

    private suspend fun writeReceivedChunk(data: ByteArray) {
        receiveMutex.withLock {
            if (completed || failed) return
            val expected = manifest?.fileSize ?: return
            if (bytesReceived + data.size > expected) {
                failTransfer(sizeFailureMessage(expected, bytesReceived + data.size), TransferErrorCodes.SIZE_MISMATCH)
                return
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    outputStream?.write(data) ?: error("저장 파일이 열려 있지 않습니다.")
                }
            }.onFailure {
                failTransfer(it.message ?: "저장 파일 쓰기 실패", TransferErrorCodes.STORAGE_WRITE_FAILED)
                return
            }
            bytesReceived += data.size
            chunkIndex += 1
            updateProgress(bytesReceived, expected)
            sendPeriodicAck()
            val now = System.currentTimeMillis()
            if (now - lastProgressAt >= 1000L) {
                lastProgressAt = now
                sendSignaling(WsMessage(type = "transfer-progress", bytesReceived = bytesReceived))
            }
            if (receiverSenderFinished && bytesReceived < expected) {
                scheduleMissingDataTimeout()
            }
            maybeFinalizeReceive()
        }
    }

    private suspend fun maybeFinalizeReceive() {
        if (completed || failed || !receiverSenderFinished) return
        val currentManifest = manifest ?: return
        if (bytesReceived != currentManifest.fileSize) return
        timeoutJob?.cancel()
        setStatus("검증 중")
        runCatching {
            withContext(Dispatchers.IO) {
                outputStream?.flush()
                outputStream?.close()
            }
        }.onFailure {
            failTransfer(it.message ?: "저장 파일 닫기 실패", TransferErrorCodes.STORAGE_CLOSE_FAILED)
            return
        }
        outputStream = null
        completed = true
        updateProgress(bytesReceived, currentManifest.fileSize)
        controlChannel?.safeSendText(
            json.encodeReceiverComplete(
                ReceiverCompleteMessage(
                    transferId = currentManifest.transferId,
                    totalBytes = bytesReceived,
                    lastChunkIndex = chunkIndex,
                ),
            ),
        )
        closeReceiveQueue(cancelWriter = false)
        sendSignaling(
            WsMessage(
                type = "transfer-completed",
                totalBytes = bytesReceived,
                directP2p = uiState.value.pathStatus == "직접 연결됨",
                turnUsed = uiState.value.pathStatus == "TURN 중계 가능성 있음",
            ),
        )
        setStatus("완료")
        stopForeground()
    }

    private fun sendPeriodicAck() {
        val now = System.currentTimeMillis()
        if (now - lastAckAt >= 1000L || chunkIndex % ACK_CHUNK_INTERVAL == 0L || bytesReceived == (manifest?.fileSize ?: -1L)) {
            sendAck(initial = false)
            lastAckAt = now
        }
    }

    private fun sendAck(initial: Boolean) {
        val currentManifest = manifest
        controlChannel?.safeSendText(
            json.encodeAck(
                AckMessage(
                    transferId = currentManifest?.transferId,
                    receivedBytes = if (initial) 0L else bytesReceived,
                    lastChunkIndex = chunkIndex,
                ),
            ),
        )
    }

    private fun scheduleMissingDataTimeout() {
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            delay(activeTimeoutMs())
            val expected = manifest?.fileSize ?: return@launch
            if (!completed && bytesReceived < expected) {
                failTransfer(sizeFailureMessage(expected, bytesReceived), TransferErrorCodes.TRANSFER_TIMEOUT)
            }
        }
    }

    private fun handleFileChannelClosed() {
        if (completed || failed) return
        val expected = manifest?.fileSize ?: sendFile?.size ?: return
        val current = if (manifest != null) bytesReceived else ackedBytes
        if (current >= expected) return
        if (receiverSenderFinished) {
            setStatus("남은 데이터 대기 중")
            scheduleMissingDataTimeout()
        } else {
            failTransfer(sizeFailureMessage(expected, current), TransferErrorCodes.DATA_CHANNEL_CLOSED)
        }
    }

    private fun hasActiveTransfer(): Boolean =
        sendingStarted || manifest != null || bytesReceived > 0L || ackedBytes > 0L

    private fun startReceiveWriter(expectedSize: Long) {
        if (outputStream == null) {
            failTransfer("저장 파일이 열려 있지 않습니다.", TransferErrorCodes.STORAGE_OPEN_FAILED)
            return
        }
        closeReceiveQueue(cancelWriter = true)
        val queue = Channel<ByteArray>(RECEIVE_QUEUE_CAPACITY)
        receiveQueue = queue
        receiveWriterJob = viewModelScope.launch {
            runCatching {
                for (chunk in queue) {
                    writeReceivedChunk(chunk)
                    if (completed || failed || bytesReceived >= expectedSize) break
                }
            }.onFailure {
                if (!completed && !failed) {
                    failTransfer(it.message ?: "수신 파일 저장 처리 실패", TransferErrorCodes.STORAGE_WRITE_FAILED)
                }
            }
        }
    }

    private fun enqueueReceivedChunk(data: ByteArray) {
        if (completed || failed) return
        val queue = receiveQueue ?: run {
            failTransfer("수신 저장 큐가 준비되지 않았습니다.", TransferErrorCodes.DATA_CHANNEL_CLOSED)
            return
        }
        if (queue.trySend(data).isFailure) {
            failTransfer(
                "수신 처리 버퍼가 가득 찼습니다. 전송 속도를 따라가지 못해 중단했습니다.",
                TransferErrorCodes.RECEIVE_QUEUE_FULL,
            )
        }
    }

    private fun closeReceiveQueue(cancelWriter: Boolean) {
        receiveQueue?.close()
        receiveQueue = null
        if (cancelWriter) {
            receiveWriterJob?.cancel()
        }
        receiveWriterJob = null
    }

    private fun failTransfer(message: String, code: String = TransferErrorCodes.UNKNOWN) {
        if (completed || failed) return
        failed = true
        timeoutJob?.cancel()
        controlChannel?.safeSendText(json.encodeError(ErrorMessage(transferId = transferId ?: manifest?.transferId, code = code, message = message)))
        sendSignaling(WsMessage(type = "transfer-failed", errorCode = code, reason = message))
        closeReceiveQueue(cancelWriter = true)
        outputStream?.closeQuietly()
        outputStream = null
        _uiState.update { it.copy(status = "실패", error = message, errorCode = code) }
        updateForeground()
        stopForeground()
    }

    private fun setError(message: String) {
        _uiState.update { it.copy(error = message, errorCode = null) }
    }

    private fun startFreshTransfer(status: String) {
        timeoutJob?.cancel()
        closeReceiveQueue(cancelWriter = true)
        signalingClient.close()
        webRtcManager?.close()
        outputStream?.closeQuietly()
        outputStream = null
        controlChannel = null
        fileChannel = null
        transferId = null
        sendingStarted = false
        senderFinished = false
        receiverComplete = false
        bytesSent = 0L
        ackedBytes = 0L
        chunksSent = 0L
        manifest = null
        bytesReceived = 0L
        chunkIndex = -1L
        receiverSenderFinished = false
        completed = false
        failed = false
        _uiState.update {
            it.copy(
                status = status,
                error = null,
                errorCode = null,
                progressBytes = 0L,
                speedBytesPerSecond = 0.0,
                etaSeconds = Double.NaN,
                roomCode = null,
                shareUrl = null,
                qrPayload = null,
                expiresAt = null,
                pathStatus = "대기 중",
                connectionState = "대기 중",
            )
        }
        lastForegroundUpdateAt = 0L
        lastForegroundStatus = ""
        foregroundActive = runCatching {
            P2PTransferForegroundService.start(getApplication(), status)
        }.isSuccess
    }

    private fun setStatus(status: String) {
        _uiState.update { it.copy(status = status) }
        updateForeground()
    }

    private fun updateProgress(bytes: Long, total: Long) {
        val elapsedSeconds = if (transferStartedAt > 0L) {
            (System.nanoTime() - transferStartedAt) / 1_000_000_000.0
        } else {
            0.0
        }
        val speed = if (elapsedSeconds > 0.0) bytes / elapsedSeconds else 0.0
        val eta = if (speed > 0.0 && total >= bytes) (total - bytes) / speed else Double.NaN
        _uiState.update {
            it.copy(
                progressBytes = bytes,
                totalBytes = total,
                speedBytesPerSecond = speed,
                etaSeconds = eta,
            )
        }
        updateForeground()
    }

    private fun updateForeground() {
        if (!foregroundActive) return
        val state = uiState.value
        val now = System.currentTimeMillis()
        if (
            state.status == lastForegroundStatus &&
            now - lastForegroundUpdateAt < FOREGROUND_UPDATE_INTERVAL_MS &&
            state.progressBytes < state.totalBytes
        ) {
            return
        }
        lastForegroundUpdateAt = now
        lastForegroundStatus = state.status
        runCatching {
            P2PTransferForegroundService.update(
                context = getApplication(),
                status = state.status,
                progressBytes = state.progressBytes,
                totalBytes = state.totalBytes,
            )
        }.onFailure {
            foregroundActive = false
        }
    }

    private fun stopForeground() {
        if (!foregroundActive) return
        foregroundActive = false
        runCatching { P2PTransferForegroundService.stop(getApplication()) }
    }

    private fun buildReceiveUrl(serverUrl: String, code: String): String =
        "${serverUrl.trimEnd('/')}/receive?code=$code"

    private fun buildAndroidIntentUrl(code: String, fallbackUrl: String): String {
        val fallback = URLEncoder.encode(fallbackUrl, "UTF-8")
        return "intent://receive?code=$code#Intent;scheme=sendhoney;package=site.sexyminup.p2pfileshare;S.browser_fallback_url=$fallback;end"
    }

    private fun resolveChunkSize(): Int = resolveDataChannelChunkSize(config?.chunkSizeBytes)

    private fun activeTimeoutMs(): Long =
        ((config?.activeTransferIdleTimeoutSeconds ?: 180).coerceAtLeast(1) * 1000L)

    private suspend fun waitForDataChannelOpen(channel: DataChannel) {
        while (channel.state() == DataChannel.State.CONNECTING) delay(25)
        if (channel.state() != DataChannel.State.OPEN) error("DataChannel이 열리지 않았습니다.")
    }

    private fun lowWaterBytes(chunkSize: Int): Long =
        maxOf(chunkSize.toLong() * 4L, LOW_WATER_BYTES)

    private fun highWaterBytes(chunkSize: Int): Long =
        maxOf(lowWaterBytes(chunkSize) + chunkSize.toLong(), HIGH_WATER_BYTES)

    private suspend fun waitForSendBuffer(channel: DataChannel, chunkSize: Int, timeoutMs: Long) {
        if (channel.bufferedAmount() <= highWaterBytes(chunkSize)) return
        waitForBufferBelow(channel, lowWaterBytes(chunkSize), timeoutMs)
    }

    private suspend fun waitForBufferBelow(channel: DataChannel, threshold: Long, timeoutMs: Long) {
        val started = System.currentTimeMillis()
        while (channel.bufferedAmount() > threshold) {
            if (channel.state() != DataChannel.State.OPEN) error("DataChannel이 열려 있지 않습니다: ${channel.state()}")
            if (System.currentTimeMillis() - started > timeoutMs) error("DataChannel backpressure timeout")
            delay(20)
        }
    }

    private suspend fun waitForDrain(channel: DataChannel, threshold: Long, timeoutMs: Long) =
        waitForBufferBelow(channel, threshold, timeoutMs)

    private suspend fun sendBinaryChunk(channel: DataChannel, payload: ByteArray, chunkSize: Int, timeoutMs: Long) {
        val started = System.currentTimeMillis()
        while (true) {
            waitForSendBuffer(channel, chunkSize, remainingTimeout(started, timeoutMs))
            if (channel.state() != DataChannel.State.OPEN) error("DataChannel이 열려 있지 않습니다: ${channel.state()}")
            if (channel.send(DataChannel.Buffer(ByteBuffer.wrap(payload), true))) return
            if (System.currentTimeMillis() - started > timeoutMs) error("DataChannel send queue is full")
            delay(20)
        }
    }

    private fun remainingTimeout(started: Long, timeoutMs: Long): Long =
        (timeoutMs - (System.currentTimeMillis() - started)).coerceAtLeast(1L)

    private fun sendSignaling(message: WsMessage) {
        runCatching { signalingClient.send(message) }
    }

    private fun DataChannel.safeSendText(text: String): Boolean =
        state() == DataChannel.State.OPEN &&
            runCatching { send(DataChannel.Buffer(ByteBuffer.wrap(text.encodeToByteArray()), false)) }
                .getOrDefault(false)

    private fun ByteBuffer.copyRemainingBytes(): ByteArray {
        val copy = slice()
        return ByteArray(copy.remaining()).also(copy::get)
    }

    private fun String.toKoreanConnectionStatus(): String = when (this) {
        "NEW", "CHECKING" -> "WebRTC 연결 중"
        "CONNECTED", "COMPLETED" -> "연결됨"
        "FAILED" -> "실패"
        "DISCONNECTED", "CLOSED" -> "연결 종료"
        else -> this
    }

    private fun sizeFailureMessage(expected: Long, received: Long): String {
        val missing = max(0L, expected - received)
        return "받은 파일 크기가 원본 파일 크기와 일치하지 않습니다. expected size: $expected, received size: $received, missing bytes: $missing"
    }

    private fun OutputStream.closeQuietly() {
        runCatching { close() }
    }

    override fun onCleared() {
        resetTransfer()
        super.onCleared()
    }

    private companion object {
        const val HIGH_WATER_BYTES = 4L * 1024L * 1024L
        const val LOW_WATER_BYTES = 1024L * 1024L
        const val ACK_CHUNK_INTERVAL = 64
        const val RECEIVE_QUEUE_CAPACITY = 512
        const val FOREGROUND_UPDATE_INTERVAL_MS = 500L
    }
}
