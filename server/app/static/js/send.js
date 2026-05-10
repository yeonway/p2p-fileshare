(function () {
    "use strict";

    var state = {
        config: null,
        room: null,
        file: null,
        ws: null,
        pc: null,
        controlChannel: null,
        fileChannel: null,
        transferId: null,
        startedAt: null,
        bytesSent: 0,
        chunksSent: 0,
        sendingStarted: false,
        lastServerProgressAt: 0,
        ttlTimer: null
    };

    function setError(message) {
        App.setText("errorText", message || "");
    }

    function updateTtl() {
        if (!state.room) {
            return;
        }
        var expiresAt = new Date(state.room.expires_at).getTime();
        var remaining = Math.max(0, Math.floor((expiresAt - Date.now()) / 1000));
        App.setText("ttlText", App.formatDuration(remaining));
    }

    async function createRoom() {
        setError("");
        state.file = App.byId("fileInput").files[0];
        if (!state.file) {
            setError("보낼 파일을 선택하세요.");
            return;
        }
        App.byId("createRoomButton").disabled = true;
        try {
            state.config = await P2P.loadConfig();
            state.room = await App.apiJson("/api/rooms", {
                method: "POST",
                body: JSON.stringify({
                    file_name: state.file.name,
                    file_size: state.file.size,
                    mime_type: state.file.type || "application/octet-stream",
                    client_type: "web"
                })
            });
            state.transferId = App.makeTransferId();
            App.setText("roomCode", state.room.code);
            App.setText("fileNameText", state.file.name);
            App.setText("fileSizeText", App.formatBytes(state.file.size));
            App.show("transferPanel", true);
            updateTtl();
            state.ttlTimer = window.setInterval(updateTtl, 1000);
            openWebSocket();
        } catch (error) {
            App.byId("createRoomButton").disabled = false;
            setError(error.message);
        }
    }

    function openWebSocket() {
        state.ws = new WebSocket(App.websocketUrl(state.room.room_id, "sender"));
        state.ws.onopen = function () {
            App.setText("receiverStatusText", "수신자 대기 중");
            App.sendWs(state.ws, { type: "sender-ready" });
        };
        state.ws.onmessage = async function (event) {
            var message = JSON.parse(event.data);
            if (message.type === "receiver-ready") {
                App.setText("receiverStatusText", "수신자 연결됨");
                await createOffer();
            } else if (message.type === "answer") {
                await state.pc.setRemoteDescription(new RTCSessionDescription(message.sdp));
            } else if (message.type === "ice-candidate" && state.pc) {
                await state.pc.addIceCandidate(P2P.normalizeCandidate(message.candidate));
            }
        };
        state.ws.onclose = function () {
            App.setText("receiverStatusText", "signaling 종료");
        };
    }

    async function createOffer() {
        if (state.pc) {
            return;
        }
        state.pc = P2P.createPeerConnection(state.config, state.ws, "sender", function (status) {
            if (status === "직접 P2P" || status === "TURN relay") {
                App.setText("pathStatusText", status);
            } else {
                App.setText("rtcStatusText", status);
            }
        });
        state.controlChannel = state.pc.createDataChannel("control", { ordered: true });
        state.fileChannel = state.pc.createDataChannel("file", { ordered: true });
        P2P.configureFileChannel(state.fileChannel, state.config.chunkSizeBytes);
        setupControlChannel();
        state.fileChannel.onopen = function () {
            App.setText("transferStatusText", "파일 채널 준비됨");
        };
        var offer = await state.pc.createOffer();
        await state.pc.setLocalDescription(offer);
        App.sendWs(state.ws, { type: "offer", sdp: state.pc.localDescription });
    }

    function setupControlChannel() {
        state.controlChannel.onopen = function () {
            var chunkSize = state.config.chunkSizeBytes;
            state.controlChannel.send(JSON.stringify({
                type: "manifest",
                transfer_id: state.transferId,
                file_name: state.file.name,
                file_size: state.file.size,
                mime_type: state.file.type || "application/octet-stream",
                chunk_size: chunkSize,
                chunk_count: Math.ceil(state.file.size / chunkSize),
                hash_algorithm: null
            }));
        };
        state.controlChannel.onmessage = async function (event) {
            var message = JSON.parse(event.data);
            if (message.type === "ack" && message.received_bytes === 0 && !state.sendingStarted) {
                await sendFileChunks();
            } else if (message.type === "ack") {
                App.updateProgress(message.received_bytes, state.file.size, state.startedAt);
            } else if (message.type === "complete") {
                App.setText("transferStatusText", "완료");
                App.updateProgress(state.file.size, state.file.size, state.startedAt);
                App.sendWs(state.ws, {
                    type: "transfer-completed",
                    total_bytes: message.total_bytes,
                    direct_p2p: App.byId("pathStatusText").textContent !== "TURN relay",
                    turn_used: App.byId("pathStatusText").textContent === "TURN relay"
                });
            } else if (message.type === "error") {
                App.setText("transferStatusText", "실패");
                setError(message.message || "전송 실패");
                App.sendWs(state.ws, { type: "transfer-failed", reason: message.message || "receiver error" });
            }
        };
    }

    async function sendFileChunks() {
        state.sendingStarted = true;
        state.startedAt = performance.now();
        state.bytesSent = 0;
        state.chunksSent = 0;
        App.setText("transferStatusText", "전송 중");
        App.sendWs(state.ws, { type: "transfer-started" });
        await P2P.waitForOpen(state.fileChannel);

        var chunkSize = state.config.chunkSizeBytes;
        for (var offset = 0; offset < state.file.size; offset += chunkSize) {
            var chunk = await state.file.slice(offset, Math.min(offset + chunkSize, state.file.size)).arrayBuffer();
            await P2P.waitForBufferedAmount(state.fileChannel, chunkSize);
            state.fileChannel.send(chunk);
            state.bytesSent += chunk.byteLength;
            state.chunksSent += 1;
            App.updateProgress(state.bytesSent, state.file.size, state.startedAt);
            var now = Date.now();
            if (now - state.lastServerProgressAt >= 1000) {
                state.lastServerProgressAt = now;
                App.sendWs(state.ws, {
                    type: "transfer-progress",
                    bytes_sent: state.bytesSent
                });
            }
        }

        state.controlChannel.send(JSON.stringify({
            type: "complete",
            transfer_id: state.transferId,
            total_bytes: state.file.size
        }));
        App.setText("transferStatusText", "수신 확인 대기 중");
    }

    document.addEventListener("DOMContentLoaded", function () {
        App.byId("createRoomButton").addEventListener("click", createRoom);
    });
}());
