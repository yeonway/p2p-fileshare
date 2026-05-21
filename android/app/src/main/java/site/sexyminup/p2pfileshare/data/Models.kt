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
