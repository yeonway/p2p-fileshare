package site.sexyminup.p2pfileshare.ui

import site.sexyminup.p2pfileshare.data.SettingsRepository

data class TransferUiState(
    val serverUrl: String = SettingsRepository.DEFAULT_SERVER_URL,
    val editableServerUrl: String = SettingsRepository.DEFAULT_SERVER_URL,
    val selectedFileName: String? = null,
    val selectedFileSize: Long = 0,
    val selectedFileMimeType: String = "application/octet-stream",
    val selectedFileCount: Int = 0,
    val receiveMimeType: String = "application/octet-stream",
    val roomCode: String? = null,
    val shareUrl: String? = null,
    val qrPayload: String? = null,
    val expiresAt: String? = null,
    val codeInput: String = "",
    val status: String = "대기 중",
    val connectionState: String = "대기 중",
    val pathStatus: String = "대기 중",
    val progressBytes: Long = 0,
    val totalBytes: Long = 0,
    val speedBytesPerSecond: Double = 0.0,
    val etaSeconds: Double = Double.NaN,
    val error: String? = null,
    val errorCode: String? = null,
    val pendingSaveFileName: String? = null,
)
