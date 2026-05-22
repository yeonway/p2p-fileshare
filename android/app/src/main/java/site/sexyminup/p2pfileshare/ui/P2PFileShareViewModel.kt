package site.sexyminup.p2pfileshare.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import site.sexyminup.p2pfileshare.P2PTransferForegroundService
import site.sexyminup.p2pfileshare.data.ApiClient
import site.sexyminup.p2pfileshare.data.SettingsRepository
import site.sexyminup.p2pfileshare.data.StoredEntryCreate
import site.sexyminup.p2pfileshare.data.StoredEntryResponse
import site.sexyminup.p2pfileshare.data.StoredTransferCreateResponse
import site.sexyminup.p2pfileshare.data.StoredTransferJoinResponse
import site.sexyminup.p2pfileshare.transfer.FileMetadata
import site.sexyminup.p2pfileshare.transfer.TransferErrorCodes
import site.sexyminup.p2pfileshare.transfer.queryFileMetadata
import site.sexyminup.p2pfileshare.transfer.tarArchiveName
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class P2PFileShareViewModel(application: Application) : AndroidViewModel(application) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val apiClient = ApiClient(OkHttpClient.Builder().build(), json)
    private val settingsRepository = SettingsRepository(application)
    private val contentResolver = application.contentResolver
    private val _uiState = MutableStateFlow(TransferUiState())
    val uiState: StateFlow<TransferUiState> = _uiState.asStateFlow()

    private var sendFiles: List<FileMetadata> = emptyList()
    private var uploadSession: StoredTransferCreateResponse? = null
    private var receiveSession: StoredTransferJoinResponse? = null
    private var saveUri: Uri? = null
    private var transferStartedAt = 0L
    private var foregroundActive = false
    private var lastForegroundUpdateAt = 0L
    private var lastForegroundStatus = ""
    private var autoResetJob: Job? = null
    private var senderCompletionPollJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.serverUrl.collect { fixedUrl ->
                _uiState.update { it.copy(serverUrl = fixedUrl, editableServerUrl = fixedUrl) }
            }
        }
        viewModelScope.launch {
            settingsRepository.autoResetOnComplete.collect { enabled ->
                _uiState.update { it.copy(autoResetOnComplete = enabled) }
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

    fun updateAutoResetOnComplete(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveAutoResetOnComplete(enabled)
            _uiState.update { it.copy(autoResetOnComplete = enabled) }
        }
    }

    fun selectSendFiles(uris: List<Uri>) {
        cancelAutoReset()
        cancelSenderCompletionPoll()
        runCatching {
            uris.distinct().map { queryFileMetadata(contentResolver, it) }
                .also { require(it.isNotEmpty()) { "보낼 파일을 선택하세요." } }
        }.onSuccess { files ->
            sendFiles = files
            uploadSession = null
            val total = files.sumOf { it.size }
            _uiState.update {
                it.copy(
                    selectedFileName = payloadName(files),
                    selectedFileSize = total,
                    selectedFileMimeType = payloadMimeType(files),
                    selectedFileCount = files.size,
                    totalBytes = total,
                    progressBytes = 0L,
                    error = null,
                    errorCode = null,
                    restartAvailable = false,
                    status = if (files.size == 1) "파일 선택됨" else "${files.size}개 파일 선택됨",
                )
            }
            if (total > MAX_TOTAL_SIZE_BYTES) {
                failTransfer("전체 전송 크기는 30GB를 넘을 수 없습니다.", TransferErrorCodes.SIZE_MISMATCH, restartable = false)
            }
        }.onFailure { setError(it.message ?: "파일 정보를 읽지 못했습니다.") }
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
        if (sendFiles.isEmpty()) {
            setError("보낼 파일을 먼저 선택하세요.")
            return
        }
        uploadSession = null
        continueUpload()
    }

    fun restartTransfer() {
        when {
            uploadSession != null && sendFiles.isNotEmpty() -> continueUpload()
            receiveSession != null && saveUri != null -> startReceiving(requireNotNull(saveUri))
            receiveSession != null -> requestSaveLocation()
            sendFiles.isNotEmpty() -> continueUpload()
            else -> setError("재시작할 전송이 없습니다.")
        }
    }

    private fun continueUpload() {
        val files = sendFiles
        if (files.isEmpty()) return
        val total = files.sumOf { it.size }
        if (total > MAX_TOTAL_SIZE_BYTES) {
            failTransfer("전체 전송 크기는 30GB를 넘을 수 없습니다.", TransferErrorCodes.SIZE_MISMATCH, restartable = false)
            return
        }
        startRuntime("업로드 준비 중", total)
        viewModelScope.launch {
            runCatching {
                val serverUrl = uiState.value.serverUrl
                val session = uploadSession ?: apiClient.createStoredTransfer(
                    serverUrl,
                    files.map {
                        StoredEntryCreate(
                            relativePath = it.name,
                            fileSize = it.size,
                            mimeType = normalizeMimeType(it.mimeType),
                        )
                    },
                ).also { uploadSession = it }
                _uiState.update {
                    val shareUrl = buildReceiveUrl(serverUrl, session.code)
                    it.copy(
                        roomCode = session.code,
                        shareUrl = shareUrl,
                        qrPayload = buildAndroidIntentUrl(serverUrl, session.code),
                        expiresAt = session.expiresAt,
                        selectedFileName = session.archiveName,
                        selectedFileSize = session.totalSize,
                        selectedFileMimeType = if (session.isBundle) "application/x-tar" else payloadMimeType(files),
                        selectedFileCount = files.size,
                        totalBytes = session.totalSize,
                    )
                }
                uploadMissingChunks(serverUrl, session, files)
                val completed = apiClient.completeStoredTransfer(serverUrl, session.transferId, session.uploadToken)
                uploadSession = session.copy(expiresAt = completed.expiresAt)
                updateProgress(completed.totalSize, completed.totalSize)
                setStatus("코드 공유 대기 중")
                stopForeground()
                if (uiState.value.autoResetOnComplete) {
                    startSenderCompletionPoll(serverUrl, session.transferId, session.uploadToken)
                }
            }.onFailure {
                failTransfer(it.message ?: "업로드 실패", TransferErrorCodes.UNKNOWN, restartable = true)
            }
        }
    }

    private suspend fun uploadMissingChunks(
        serverUrl: String,
        session: StoredTransferCreateResponse,
        files: List<FileMetadata>,
    ) {
        startForeground("업로드 중")
        setStatus("업로드 중")
        val status = apiClient.getStoredStatus(serverUrl, session.transferId, uploadToken = session.uploadToken)
        var uploadedBytes = status.entries.sumOf { it.bytesUploaded }
        transferStartedAt = System.nanoTime()
        updateProgress(uploadedBytes, status.totalSize)
        status.entries.forEachIndexed { index, entry ->
            val file = files[index]
            uploadEntry(serverUrl, session, entry, file) { sent ->
                uploadedBytes += sent
                updateProgress(uploadedBytes, status.totalSize)
            }
        }
    }

    private suspend fun uploadEntry(
        serverUrl: String,
        session: StoredTransferCreateResponse,
        entry: StoredEntryResponse,
        file: FileMetadata,
        onSent: (Long) -> Unit,
    ) {
        val uploaded = entry.uploadedChunks.toSet()
        withContext(Dispatchers.IO) {
            contentResolver.openInputStream(file.uri).use { rawInput ->
                val input = requireNotNull(rawInput) { "파일을 열 수 없습니다: ${file.name}" }
                for (chunkIndex in 0 until entry.chunkCount) {
                    val length = chunkLength(file.size, session.chunkSizeBytes, chunkIndex)
                    if (chunkIndex in uploaded) {
                        input.skipFully(length.toLong())
                        continue
                    }
                    val bytes = input.readExactly(length)
                    apiClient.uploadStoredChunk(
                        baseUrl = serverUrl,
                        transferId = session.transferId,
                        entryId = entry.entryId,
                        chunkIndex = chunkIndex,
                        uploadToken = session.uploadToken,
                        bytes = bytes,
                    )
                    onSent(bytes.size.toLong())
                }
            }
        }
    }

    fun joinReceiveRoom() {
        val code = uiState.value.codeInput
        if (!Regex("^\\d{6}$").matches(code)) {
            setError("6자리 코드를 입력하세요.")
            return
        }
        startRuntime("서버 연결 중", 0L)
        uploadSession = null
        viewModelScope.launch {
            runCatching {
                val joined = apiClient.joinStoredTransfer(uiState.value.serverUrl, code)
                receiveSession = joined
                val receiveMimeType = if (joined.isBundle) "application/x-tar" else normalizeMimeType(joined.entries.firstOrNull()?.mimeType)
                _uiState.update {
                    it.copy(
                        status = "저장 위치 선택 대기 중",
                        selectedFileName = joined.archiveName,
                        selectedFileSize = joined.downloadSize,
                        selectedFileMimeType = receiveMimeType,
                        selectedFileCount = joined.entries.size,
                        receiveMimeType = receiveMimeType,
                        totalBytes = joined.downloadSize,
                        progressBytes = 0L,
                        error = null,
                        errorCode = null,
                        restartAvailable = false,
                    )
                }
            }.onFailure {
                failTransfer(it.message ?: "방 참가 실패", TransferErrorCodes.UNKNOWN, restartable = true)
            }
        }
    }

    fun requestSaveLocation() {
        val joined = receiveSession ?: run {
            setError("먼저 6자리 코드로 받을 파일을 조회하세요.")
            return
        }
        _uiState.update { it.copy(pendingSaveFileName = joined.archiveName) }
    }

    fun consumeSavePickerRequest() {
        _uiState.update { it.copy(pendingSaveFileName = null) }
    }

    fun reportPickerError(message: String) {
        _uiState.update {
            it.copy(
                status = "파일 선택 실패",
                error = message.ifBlank { "파일 선택기를 열 수 없습니다." },
                errorCode = TransferErrorCodes.STORAGE_OPEN_FAILED,
                pendingSaveFileName = null,
                restartAvailable = true,
            )
        }
    }

    fun startReceiving(uri: Uri) {
        val joined = receiveSession ?: return
        saveUri = uri
        startRuntime("다운로드 중", joined.downloadSize)
        viewModelScope.launch {
            runCatching {
                startForeground("다운로드 중")
                transferStartedAt = System.nanoTime()
                var received = 0L
                withContext(Dispatchers.IO) {
                    apiClient.openStoredDownload(
                        baseUrl = uiState.value.serverUrl,
                        transferId = joined.transferId,
                        downloadToken = joined.downloadToken,
                    ).use { response ->
                        val input = requireNotNull(response.body) { "다운로드 응답이 비어 있습니다." }.byteStream()
                        contentResolver.openOutputStream(uri, "w").use { rawOutput ->
                            val output = requireNotNull(rawOutput) { "저장 파일을 열 수 없습니다." }
                            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                received += read
                                updateProgress(received, joined.downloadSize)
                            }
                            output.flush()
                        }
                    }
                }
                updateProgress(joined.downloadSize, joined.downloadSize)
                setStatus("완료")
                stopForeground()
                if (uiState.value.autoResetOnComplete) scheduleAutoResetAfterComplete()
            }.onFailure {
                failTransfer(it.message ?: "다운로드 실패", TransferErrorCodes.UNKNOWN, restartable = true)
            }
        }
    }

    fun resetTransfer() {
        cancelAutoReset()
        cancelSenderCompletionPoll()
        stopForeground()
        sendFiles = emptyList()
        uploadSession = null
        receiveSession = null
        saveUri = null
        transferStartedAt = 0L
        _uiState.update {
            TransferUiState(
                serverUrl = SettingsRepository.DEFAULT_SERVER_URL,
                editableServerUrl = SettingsRepository.DEFAULT_SERVER_URL,
                codeInput = it.codeInput,
                autoResetOnComplete = it.autoResetOnComplete,
            )
        }
    }

    private fun startRuntime(status: String, totalBytes: Long) {
        cancelAutoReset()
        cancelSenderCompletionPoll()
        _uiState.update {
            it.copy(
                status = status,
                error = null,
                errorCode = null,
                restartAvailable = false,
                progressBytes = 0L,
                totalBytes = totalBytes,
                speedBytesPerSecond = 0.0,
                etaSeconds = Double.NaN,
                pathStatus = "Pi 임시 저장",
                connectionState = "HTTP",
                roomCode = if (status.startsWith("업로드")) it.roomCode else null,
                shareUrl = if (status.startsWith("업로드")) it.shareUrl else null,
                qrPayload = if (status.startsWith("업로드")) it.qrPayload else null,
            )
        }
        lastForegroundUpdateAt = 0L
        lastForegroundStatus = ""
    }

    private fun failTransfer(message: String, code: String, restartable: Boolean) {
        stopForeground()
        _uiState.update {
            it.copy(
                status = "실패",
                error = message,
                errorCode = code,
                restartAvailable = restartable,
            )
        }
    }

    private fun setError(message: String) {
        _uiState.update { it.copy(error = message, errorCode = null) }
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

    private fun startForeground(status: String) {
        if (foregroundActive) return
        foregroundActive = runCatching {
            P2PTransferForegroundService.start(
                context = getApplication(),
                status = status,
                progressBytes = uiState.value.progressBytes,
                totalBytes = uiState.value.totalBytes,
            )
        }.isSuccess
        lastForegroundUpdateAt = 0L
        lastForegroundStatus = ""
    }

    private fun stopForeground() {
        if (!foregroundActive) return
        foregroundActive = false
        runCatching { P2PTransferForegroundService.stop(getApplication()) }
    }

    private fun scheduleAutoResetAfterComplete() {
        cancelAutoReset()
        autoResetJob = viewModelScope.launch {
            delay(AUTO_RESET_DELAY_MS)
            autoResetJob = null
            resetTransfer()
        }
    }

    private fun cancelAutoReset() {
        autoResetJob?.cancel()
        autoResetJob = null
    }

    private fun startSenderCompletionPoll(serverUrl: String, transferId: String, uploadToken: String) {
        cancelSenderCompletionPoll()
        senderCompletionPollJob = viewModelScope.launch {
            while (true) {
                delay(5_000L)
                val missing = runCatching {
                    apiClient.getStoredStatus(serverUrl, transferId, uploadToken = uploadToken)
                }.fold(
                    onSuccess = { false },
                    onFailure = { error ->
                        val message = error.message.orEmpty()
                        message.contains("not found", ignoreCase = true) ||
                            message.contains("expired", ignoreCase = true)
                    },
                )
                if (missing) {
                    senderCompletionPollJob = null
                    if (uiState.value.autoResetOnComplete) {
                        scheduleAutoResetAfterComplete()
                    }
                    break
                }
            }
        }
    }

    private fun cancelSenderCompletionPoll() {
        senderCompletionPollJob?.cancel()
        senderCompletionPollJob = null
    }

    private fun buildReceiveUrl(serverUrl: String, code: String): String =
        "${serverUrl.trimEnd('/')}/receive?code=$code"

    private fun buildAndroidIntentUrl(serverUrl: String, code: String): String {
        val fallback = URLEncoder.encode(buildReceiveUrl(serverUrl, code), StandardCharsets.UTF_8.name())
        return "intent://receive?code=$code#Intent;scheme=sendhoney;package=site.sexyminup.p2pfileshare;S.browser_fallback_url=$fallback;end"
    }

    private fun payloadName(files: List<FileMetadata>): String =
        if (files.size == 1) files.first().name else tarArchiveName(files.size)

    private fun payloadMimeType(files: List<FileMetadata>): String =
        if (files.size == 1) normalizeMimeType(files.first().mimeType) else "application/x-tar"

    private fun normalizeMimeType(value: String?): String {
        val normalized = value.orEmpty().trim().lowercase()
        return if (normalized.contains("/") && !normalized.contains(";") && normalized.length <= 100) {
            normalized
        } else {
            "application/octet-stream"
        }
    }

    private fun chunkLength(fileSize: Long, chunkSize: Int, chunkIndex: Int): Int {
        val start = chunkIndex.toLong() * chunkSize.toLong()
        return minOf(chunkSize.toLong(), fileSize - start).toInt()
    }

    private fun InputStream.readExactly(size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(buffer, offset, size - offset)
            if (read < 0) error("파일을 읽는 중 끝에 도달했습니다.")
            offset += read
        }
        return buffer
    }

    private fun InputStream.skipFully(size: Long) {
        var remaining = size
        val scratch = ByteArray(64 * 1024)
        while (remaining > 0L) {
            val skipped = skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
                continue
            }
            val read = read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (read < 0) error("파일을 건너뛰는 중 끝에 도달했습니다.")
            remaining -= read
        }
    }

    override fun onCleared() {
        cancelAutoReset()
        cancelSenderCompletionPoll()
        stopForeground()
        super.onCleared()
    }

    private companion object {
        const val MAX_TOTAL_SIZE_BYTES = 30L * 1024L * 1024L * 1024L
        const val DOWNLOAD_BUFFER_SIZE = 256 * 1024
        const val FOREGROUND_UPDATE_INTERVAL_MS = 500L
        const val AUTO_RESET_DELAY_MS = 2000L
    }
}
