package site.sexyminup.p2pfileshare.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ConfigResponse(
    val iceServers: List<IceServer> = emptyList(),
    val roomTtlMinutes: Int,
    val activeTransferIdleTimeoutSeconds: Int,
    val chunkSizeBytes: Int,
)

@Serializable
data class IceServer(
    val urls: JsonElement,
    val username: String? = null,
    val credential: String? = null,
)

@Serializable
data class RoomCreateRequest(
    @SerialName("file_name") val fileName: String,
    @SerialName("file_size") val fileSize: Long,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("client_type") val clientType: String,
)

@Serializable
data class RoomCreateResponse(
    @SerialName("room_id") val roomId: String,
    val code: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class RoomJoinRequest(
    val code: String,
    @SerialName("client_type") val clientType: String,
)

@Serializable
data class RoomJoinResponse(
    @SerialName("room_id") val roomId: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("file_size") val fileSize: Long,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class ApiError(val detail: String = "")

@Serializable
data class StoredEntryCreate(
    @SerialName("relative_path") val relativePath: String,
    @SerialName("file_size") val fileSize: Long,
    @SerialName("mime_type") val mimeType: String = "application/octet-stream",
)

@Serializable
data class StoredTransferCreateRequest(
    @SerialName("client_type") val clientType: String,
    val entries: List<StoredEntryCreate>,
)

@Serializable
data class StoredEntryResponse(
    @SerialName("entry_id") val entryId: String,
    @SerialName("relative_path") val relativePath: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("file_size") val fileSize: Long,
    @SerialName("mime_type") val mimeType: String = "application/octet-stream",
    @SerialName("chunk_count") val chunkCount: Int,
    @SerialName("uploaded_chunks") val uploadedChunks: List<Int> = emptyList(),
    @SerialName("bytes_uploaded") val bytesUploaded: Long = 0,
    val completed: Boolean = false,
)

@Serializable
data class StoredTransferCreateResponse(
    @SerialName("transfer_id") val transferId: String,
    val code: String,
    @SerialName("upload_token") val uploadToken: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("chunk_size_bytes") val chunkSizeBytes: Int,
    @SerialName("max_total_size_bytes") val maxTotalSizeBytes: Long,
    @SerialName("total_size") val totalSize: Long,
    @SerialName("is_bundle") val isBundle: Boolean,
    @SerialName("archive_name") val archiveName: String,
    val entries: List<StoredEntryResponse>,
)

@Serializable
data class StoredTransferJoinRequest(
    val code: String,
    @SerialName("client_type") val clientType: String,
)

@Serializable
data class StoredTransferJoinResponse(
    @SerialName("transfer_id") val transferId: String,
    @SerialName("download_token") val downloadToken: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("total_size") val totalSize: Long,
    @SerialName("download_size") val downloadSize: Long,
    @SerialName("is_bundle") val isBundle: Boolean,
    @SerialName("archive_name") val archiveName: String,
    val entries: List<StoredEntryResponse>,
)

@Serializable
data class StoredTransferStatusResponse(
    @SerialName("transfer_id") val transferId: String,
    val status: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("chunk_size_bytes") val chunkSizeBytes: Int,
    @SerialName("total_size") val totalSize: Long,
    @SerialName("download_size") val downloadSize: Long,
    @SerialName("is_bundle") val isBundle: Boolean,
    @SerialName("archive_name") val archiveName: String,
    val entries: List<StoredEntryResponse>,
)
