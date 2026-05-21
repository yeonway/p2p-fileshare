package site.sexyminup.p2pfileshare.signaling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class SignalingClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    private var webSocket: WebSocket? = null

    fun connect(
        baseUrl: String,
        roomId: String,
        role: String,
        onMessage: (WsMessage) -> Unit,
        onOpen: () -> Unit,
        onClosed: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        close()
        val request = Request.Builder()
            .url(webSocketUrl(baseUrl, roomId, role))
            .build()
        webSocket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = onOpen()

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching { json.decodeFromString<WsMessage>(text) }
                        .onSuccess(onMessage)
                        .onFailure(onFailure)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosed()

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onFailure(t)
            },
        )
    }

    fun send(message: WsMessage) {
        webSocket?.send(json.encodeToString(message))
    }

    fun close() {
        webSocket?.close(1000, "closed")
        webSocket = null
    }

    private fun webSocketUrl(baseUrl: String, roomId: String, role: String): String {
        val clean = baseUrl.trimEnd('/')
        val wsBase = when {
            clean.startsWith("https://") -> "wss://" + clean.removePrefix("https://")
            clean.startsWith("http://") -> "ws://" + clean.removePrefix("http://")
            else -> clean
        }
        return "$wsBase/ws/$roomId/$role"
    }
}

@Serializable
data class WsMessage(
    val type: String,
    val sdp: SessionDescriptionDto? = null,
    val candidate: IceCandidateDto? = null,
    val role: String? = null,
    @SerialName("bytes_sent") val bytesSent: Long? = null,
    @SerialName("bytes_received") val bytesReceived: Long? = null,
    @SerialName("total_bytes") val totalBytes: Long? = null,
    @SerialName("direct_p2p") val directP2p: Boolean? = null,
    @SerialName("turn_used") val turnUsed: Boolean? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val reason: String? = null,
)

@Serializable
data class SessionDescriptionDto(
    val type: String,
    val sdp: String,
)

@Serializable
data class IceCandidateDto(
    val sdpMid: String? = null,
    val sdpMLineIndex: Int = 0,
    val candidate: String,
)
