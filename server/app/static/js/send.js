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
        ackedBytes: 0,
        chunksSent: 0,
        sendingStarted: false,
        senderFinished: false,
        receiverComplete: false,
        effectiveChunkSize: null,
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
            await renderShareQr(state.room.code);
            App.show("transferPanel", true);
            App.startTransferKeepAlive("파일 전송 방이 열려 있습니다.");
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
            if (!state.receiverComplete && state.sendingStarted) {
                failTransfer(App.ErrorCodes.SIGNALING_CLOSED, "signaling 연결이 전송 중 종료되었습니다.");
            } else {
                App.setText("receiverStatusText", "signaling 종료");
            }
        };
    }

    async function renderShareQr(code) {
        var receiveUrl = App.buildReceiveUrl(code);
        var intentUrl = App.buildAndroidIntentUrl(code);
        var link = App.byId("shareLink");
        if (link) {
            link.href = receiveUrl;
            link.textContent = receiveUrl;
        }
        await App.renderQrCode("qrCode", intentUrl);
        App.show("qrPanel", true);
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
            state.effectiveChunkSize = P2P.resolveChunkSize(state.config, state.pc);
            P2P.configureFileChannel(state.fileChannel, state.effectiveChunkSize);
            state.controlChannel.send(JSON.stringify({
                type: "manifest",
                transfer_id: state.transferId,
                file_name: state.file.name,
                file_size: state.file.size,
                mime_type: state.file.type || "application/octet-stream",
                chunk_size: state.effectiveChunkSize,
                chunk_count: Math.ceil(state.file.size / state.effectiveChunkSize),
                hash_algorithm: null
            }));
        };
        state.controlChannel.onmessage = async function (event) {
            var message = JSON.parse(event.data);
            if (message.type === "ack") {
                handleAck(message);
                if (message.received_bytes === 0 && !state.sendingStarted) {
                    try {
                        await sendFileChunks();
                    } catch (error) {
                        failTransfer(App.ErrorCodes.DATA_CHANNEL_TIMEOUT, error.message);
                    }
                }
            } else if (message.type === "receiver-complete" || message.type === "complete") {
                handleReceiverComplete(message);
            } else if (message.type === "error") {
                failTransfer(message.code || App.ErrorCodes.REMOTE_ERROR, message.message || "수신자 오류");
            }
        };
    }

    function handleAck(message) {
        if (typeof message.received_bytes !== "number") {
            return;
        }
        state.ackedBytes = Math.max(state.ackedBytes, message.received_bytes);
        App.updateProgress(state.ackedBytes, state.file.size, state.startedAt);
        if (!state.receiverComplete && state.senderFinished) {
            App.setText("transferStatusText", "수신 완료 확인 대기 중");
        }
    }

    function handleReceiverComplete(message) {
        if (message.total_bytes !== state.file.size) {
            failTransfer(App.ErrorCodes.SIZE_MISMATCH, "수신 완료 크기가 원본과 일치하지 않습니다.");
            return;
        }
        state.receiverComplete = true;
        App.setText("transferStatusText", "완료");
        App.updateProgress(state.file.size, state.file.size, state.startedAt);
        App.stopTransferKeepAlive();
    }

    function failTransfer(code, message) {
        if (message === undefined) {
            message = code;
            code = App.ErrorCodes.UNKNOWN;
        }
        App.setText("transferStatusText", "실패");
        setError(App.displayError(code, message));
        App.sendWs(state.ws, { type: "transfer-failed", error_code: code, reason: message });
        App.stopTransferKeepAlive();
    }

    async function sendFileChunks() {
        state.sendingStarted = true;
        state.startedAt = performance.now();
        state.bytesSent = 0;
        state.ackedBytes = 0;
        state.chunksSent = 0;
        App.setText("transferStatusText", "전송 중");
        App.updateProgress(0, state.file.size, state.startedAt);
        App.sendWs(state.ws, { type: "transfer-started" });
        await P2P.waitForOpen(state.fileChannel);

        var chunkSize = state.effectiveChunkSize || P2P.resolveChunkSize(state.config, state.pc);
        var backpressureTimeoutMs = (state.config.activeTransferIdleTimeoutSeconds || 180) * 1000;
        for (var offset = 0; offset < state.file.size; offset += chunkSize) {
            await P2P.waitForBufferedAmount(state.fileChannel, chunkSize);
            var chunk = await state.file.slice(offset, Math.min(offset + chunkSize, state.file.size)).arrayBuffer();
            if (state.fileChannel.readyState !== "open") {
                throw new Error("file DataChannel이 전송 중 닫혔습니다.");
            }
            await P2P.sendBinary(state.fileChannel, chunk, chunkSize, backpressureTimeoutMs);
            state.bytesSent += chunk.byteLength;
            state.chunksSent += 1;
            var now = Date.now();
            if (now - state.lastServerProgressAt >= 1000) {
                state.lastServerProgressAt = now;
                App.sendWs(state.ws, {
                    type: "transfer-progress",
                    bytes_sent: state.bytesSent
                });
            }
        }

        App.setText("transferStatusText", "전송 큐 비우는 중");
        await P2P.waitForDrain(
            state.fileChannel,
            state.fileChannel.bufferedAmountLowThreshold || chunkSize,
            (state.config.activeTransferIdleTimeoutSeconds || 180) * 1000
        );
        state.senderFinished = true;
        state.controlChannel.send(JSON.stringify({
            type: "sender-finished",
            transfer_id: state.transferId,
            total_bytes: state.file.size,
            last_chunk_index: state.chunksSent - 1
        }));
        App.setText("transferStatusText", "수신 완료 확인 대기 중");
    }

    document.addEventListener("DOMContentLoaded", function () {
        App.byId("createRoomButton").addEventListener("click", createRoom);
    });
}());
