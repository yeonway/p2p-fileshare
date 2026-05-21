package site.sexyminup.p2pfileshare.transfer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ManifestMessage(
    val type: String = "manifest",
    @SerialName("transfer_id") val transferId: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("file_size") val fileSize: Long,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("chunk_size") val chunkSize: Int,
    @SerialName("chunk_count") val chunkCount: Long,
    @SerialName("hash_algorithm") val hashAlgorithm: String? = null,
)

@Serializable
data class AckMessage(
    val type: String = "ack",
    @SerialName("transfer_id") val transferId: String? = null,
    @SerialName("received_bytes") val receivedBytes: Long,
    @SerialName("last_chunk_index") val lastChunkIndex: Long,
)

@Serializable
data class SenderFinishedMessage(
    val type: String = "sender-finished",
    @SerialName("transfer_id") val transferId: String,
    @SerialName("total_bytes") val totalBytes: Long,
    @SerialName("last_chunk_index") val lastChunkIndex: Long,
)

@Serializable
data class ReceiverCompleteMessage(
    val type: String = "receiver-complete",
    @SerialName("transfer_id") val transferId: String,
    @SerialName("total_bytes") val totalBytes: Long,
    @SerialName("last_chunk_index") val lastChunkIndex: Long,
)

@Serializable
data class ErrorMessage(
    val type: String = "error",
    @SerialName("transfer_id") val transferId: String? = null,
    val code: String = TransferErrorCodes.UNKNOWN,
    val message: String,
)

object TransferErrorCodes {
    const val UNKNOWN = "E_UNKNOWN"
    const val SIGNALING_CLOSED = "E_SIGNALING_CLOSED"
    const val SIGNALING_ERROR = "E_SIGNALING_ERROR"
    const val PEER_CONNECTION_FAILED = "E_PEER_CONNECTION_FAILED"
    const val DATA_CHANNEL_CLOSED = "E_DATA_CHANNEL_CLOSED"
    const val DATA_CHANNEL_TIMEOUT = "E_DATA_CHANNEL_TIMEOUT"
    const val RECEIVE_QUEUE_FULL = "E_RECEIVE_QUEUE_FULL"
    const val TRANSFER_TIMEOUT = "E_TRANSFER_TIMEOUT"
    const val SIZE_MISMATCH = "E_SIZE_MISMATCH"
    const val METADATA_MISMATCH = "E_METADATA_MISMATCH"
    const val STORAGE_OPEN_FAILED = "E_STORAGE_OPEN_FAILED"
    const val STORAGE_WRITE_FAILED = "E_STORAGE_WRITE_FAILED"
    const val STORAGE_CLOSE_FAILED = "E_STORAGE_CLOSE_FAILED"
    const val REMOTE_ERROR = "E_REMOTE_ERROR"
}

fun Json.messageType(text: String): String {
    return decodeFromString<JsonObject>(text)["type"]?.jsonPrimitive?.content.orEmpty()
}

fun Json.encodeManifest(message: ManifestMessage): String = encodeToString(message)
fun Json.encodeAck(message: AckMessage): String = encodeToString(message)
fun Json.encodeSenderFinished(message: SenderFinishedMessage): String = encodeToString(message)
fun Json.encodeReceiverComplete(message: ReceiverCompleteMessage): String = encodeToString(message)
fun Json.encodeError(message: ErrorMessage): String = encodeToString(message)
