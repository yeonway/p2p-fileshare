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
    val message: String,
)

fun Json.messageType(text: String): String {
    return decodeFromString<JsonObject>(text)["type"]?.jsonPrimitive?.content.orEmpty()
}

fun Json.encodeManifest(message: ManifestMessage): String = encodeToString(message)
fun Json.encodeAck(message: AckMessage): String = encodeToString(message)
fun Json.encodeSenderFinished(message: SenderFinishedMessage): String = encodeToString(message)
fun Json.encodeReceiverComplete(message: ReceiverCompleteMessage): String = encodeToString(message)
fun Json.encodeError(message: ErrorMessage): String = encodeToString(message)
