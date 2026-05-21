package site.sexyminup.p2pfileshare.webrtc

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import site.sexyminup.p2pfileshare.data.ConfigResponse
import site.sexyminup.p2pfileshare.data.IceServer
import site.sexyminup.p2pfileshare.signaling.IceCandidateDto
import site.sexyminup.p2pfileshare.signaling.SessionDescriptionDto
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebRtcManager(
    context: Context,
    private val onIceCandidate: (IceCandidateDto) -> Unit,
    private val onConnectionState: (String) -> Unit,
    private val onDataChannel: (DataChannel) -> Unit,
) {
    private val factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    fun createPeerConnection(config: ConfigResponse): PeerConnection {
        close()
        val rtcConfig = PeerConnection.RTCConfiguration(config.iceServers.map(::toIceServer)).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = factory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    onConnectionState(state?.name ?: "UNKNOWN")
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
                override fun onIceCandidate(candidate: IceCandidate?) {
                    if (candidate != null) {
                        onIceCandidate(
                            IceCandidateDto(
                                sdpMid = candidate.sdpMid,
                                sdpMLineIndex = candidate.sdpMLineIndex,
                                candidate = candidate.sdp,
                            ),
                        )
                    }
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit
                override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit
                override fun onDataChannel(channel: DataChannel?) {
                    if (channel != null) onDataChannel(channel)
                }

                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) = Unit
            },
        ) ?: error("PeerConnection 생성에 실패했습니다.")
        peerConnection = pc
        return pc
    }

    fun createDataChannel(label: String): DataChannel {
        val init = DataChannel.Init().apply { ordered = true }
        return requireNotNull(peerConnection).createDataChannel(label, init)
    }

    suspend fun createOffer(): SessionDescriptionDto {
        val pc = requireNotNull(peerConnection)
        val sdp = pc.awaitCreateOffer()
        pc.awaitSetLocalDescription(sdp)
        return sdp.toDto()
    }

    suspend fun createAnswer(): SessionDescriptionDto {
        val pc = requireNotNull(peerConnection)
        val sdp = pc.awaitCreateAnswer()
        pc.awaitSetLocalDescription(sdp)
        return sdp.toDto()
    }

    suspend fun setRemoteDescription(dto: SessionDescriptionDto) {
        requireNotNull(peerConnection).awaitSetRemoteDescription(dto.toSessionDescription())
    }

    fun addIceCandidate(dto: IceCandidateDto) {
        peerConnection?.addIceCandidate(IceCandidate(dto.sdpMid, dto.sdpMLineIndex, dto.candidate))
    }

    fun reportConnectionPath(onPath: (direct: Boolean?, turn: Boolean?) -> Unit) {
        peerConnection?.getStats { report: RTCStatsReport ->
            val reports = report.statsMap.values
            val selectedPair = reports.firstOrNull {
                it.type == "candidate-pair" &&
                    (it.members["selected"] == true || it.members["nominated"] == true) &&
                    it.members["state"] == "succeeded"
            }
            val localId = selectedPair?.members?.get("localCandidateId") as? String
            val remoteId = selectedPair?.members?.get("remoteCandidateId") as? String
            val turnUsed = reports.any {
                (it.id == localId || it.id == remoteId) && it.members["candidateType"] == "relay"
            }
            if (selectedPair == null) onPath(null, null) else onPath(!turnUsed, turnUsed)
        }
    }

    fun close() {
        peerConnection?.close()
        peerConnection = null
    }

    private fun toIceServer(server: IceServer): PeerConnection.IceServer {
        val urls = when (val value = server.urls) {
            is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull }
            is JsonPrimitive -> listOfNotNull(value.contentOrNull)
            else -> emptyList()
        }
        val builder = PeerConnection.IceServer.builder(urls)
        server.username?.let(builder::setUsername)
        server.credential?.let(builder::setPassword)
        return builder.createIceServer()
    }
}

private suspend fun PeerConnection.awaitCreateOffer(): SessionDescription =
    suspendCancellableCoroutine { continuation ->
        createOffer(
            object : SdpObserverAdapter() {
                override fun onCreateSuccess(description: SessionDescription) = continuation.resume(description)
                override fun onCreateFailure(error: String) = continuation.resumeWithException(IllegalStateException(error))
            },
            MediaConstraints(),
        )
    }

private suspend fun PeerConnection.awaitCreateAnswer(): SessionDescription =
    suspendCancellableCoroutine { continuation ->
        createAnswer(
            object : SdpObserverAdapter() {
                override fun onCreateSuccess(description: SessionDescription) = continuation.resume(description)
                override fun onCreateFailure(error: String) = continuation.resumeWithException(IllegalStateException(error))
            },
            MediaConstraints(),
        )
    }

private suspend fun PeerConnection.awaitSetLocalDescription(description: SessionDescription): Unit =
    suspendCancellableCoroutine { continuation ->
        setLocalDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() = continuation.resume(Unit)
                override fun onSetFailure(error: String) = continuation.resumeWithException(IllegalStateException(error))
            },
            description,
        )
    }

private suspend fun PeerConnection.awaitSetRemoteDescription(description: SessionDescription): Unit =
    suspendCancellableCoroutine { continuation ->
        setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() = continuation.resume(Unit)
                override fun onSetFailure(error: String) = continuation.resumeWithException(IllegalStateException(error))
            },
            description,
        )
    }

private open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String) = Unit
    override fun onSetFailure(error: String) = Unit
}

private fun SessionDescription.toDto(): SessionDescriptionDto =
    SessionDescriptionDto(type = type.canonicalForm(), sdp = description)

private fun SessionDescriptionDto.toSessionDescription(): SessionDescription =
    SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)
