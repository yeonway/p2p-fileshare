(function () {
    "use strict";

    var ACK_CHUNK_INTERVAL = 64;

    var state = {
        config: null,
        room: null,
        ws: null,
        pc: null,
        controlChannel: null,
        fileChannel: null,
        manifest: null,
        writer: null,
        fallbackChunks: [],
        fallbackMode: false,
        downloadUrl: null,
        writeQueue: Promise.resolve(),
        bytesReceived: 0,
        chunkIndex: -1,
        startedAt: null,
        lastAckAt: 0,
        lastServerProgressAt: 0,
        senderFinished: false,
        finalizing: false,
        completed: false,
        failed: false,
        missingDataTimer: null
    };

    function setError(message) {
        App.setText("errorText", message || "");
    }

    async function lookupRoom() {
        setError("");
        var code = App.byId("codeInput").value.trim();
        if (!/^\d{6}$/.test(code)) {
            setError("6자리 코드를 입력하세요.");
            return;
        }
        App.byId("lookupButton").disabled = true;
        try {
            state.config = await P2P.loadConfig();
            state.room = await App.apiJson("/api/rooms/join", {
                method: "POST",
                body: JSON.stringify({ code: code, client_type: "web" })
            });
            App.setText("fileNameText", state.room.file_name);
            App.setText("fileSizeText", App.formatBytes(state.room.file_size));
            App.setText("mimeText", state.room.mime_type || "application/octet-stream");
            App.show("transferPanel", true);
            App.show("connectButton", true);
        } catch (error) {
            App.byId("lookupButton").disabled = false;
            setError(error.message);
        }
    }

    async function prepareAndConnect() {
        setError("");
        App.byId("connectButton").disabled = true;
        try {
            await prepareWriter();
            App.startTransferKeepAlive("파일 수신 연결이 열려 있습니다.");
            openWebSocket();
        } catch (error) {
            App.byId("connectButton").disabled = false;
            if (isUserCancel(error)) {
                App.setText("transferStatusText", "저장 위치 선택 취소됨");
                setError("");
                return;
            }
            setError(App.displayError(App.ErrorCodes.STORAGE_OPEN_FAILED, "저장 준비 중 문제가 발생했습니다."));
        }
    }

    async function prepareWriter() {
        if (typeof window.showSaveFilePicker !== "function") {
            enableBlobFallback();
            return;
        }

        try {
            var handle = await window.showSaveFilePicker({
                suggestedName: state.room.file_name
            });
            try {
                state.writer = await handle.createWritable();
            } catch (error) {
                enableBlobFallback();
                return;
            }
            state.fallbackMode = false;
            state.fallbackChunks = [];
            clearDownloadLink();
            App.show("fallbackWarning", false);
        } catch (error) {
            if (isUserCancel(error)) {
                throw error;
            }
            enableBlobFallback();
        }
    }

    function enableBlobFallback() {
        state.writer = null;
        state.fallbackMode = true;
        state.fallbackChunks = [];
        clearDownloadLink();
        App.show("fallbackWarning", true);
        App.setText("transferStatusText", "브라우저 다운로드 모드");
        setError("");
    }

    function clearDownloadLink() {
        if (state.downloadUrl) {
            URL.revokeObjectURL(state.downloadUrl);
            state.downloadUrl = null;
        }
        var link = App.byId("downloadLink");
        link.removeAttribute("href");
        link.removeAttribute("download");
        App.show("downloadLink", false);
    }

    function isUserCancel(error) {
        var name = error && error.name ? String(error.name) : "";
        var message = error && error.message ? String(error.message).toLowerCase() : "";
        return name === "AbortError" || message.indexOf("abort") >= 0 || message.indexOf("cancel") >= 0;
    }

    function openWebSocket() {
        state.ws = new WebSocket(App.websocketUrl(state.room.room_id, "receiver"));
        state.ws.onopen = function () {
            App.setText("transferStatusText", "송신자 연결 대기 중");
            App.sendWs(state.ws, { type: "receiver-ready" });
        };
        state.ws.onmessage = async function (event) {
            var message = JSON.parse(event.data);
            if (message.type === "offer") {
                await acceptOffer(message.sdp);
            } else if (message.type === "ice-candidate" && state.pc) {
                await state.pc.addIceCandidate(P2P.normalizeCandidate(message.candidate));
            }
        };
        state.ws.onclose = function () {
            if (!state.completed && (state.bytesReceived > 0 || state.senderFinished)) {
                failTransfer(App.ErrorCodes.SIGNALING_CLOSED, "signaling 연결이 수신 중 종료되었습니다.");
            }
        };
        state.ws.onerror = function () {
            failTransfer(App.ErrorCodes.SIGNALING_ERROR, "WebSocket signaling 오류");
        };
    }

    async function acceptOffer(sdp) {
        state.pc = P2P.createPeerConnection(state.config, state.ws, "receiver", function (status) {
            if (status === "직접 P2P" || status === "TURN relay") {
                App.setText("pathStatusText", status);
            } else {
                App.setText("rtcStatusText", status);
            }
        });
        state.pc.ondatachannel = function (event) {
            if (event.channel.label === "control") {
                state.controlChannel = event.channel;
                setupControlChannel();
            } else if (event.channel.label === "file") {
                state.fileChannel = event.channel;
                P2P.configureFileChannel(state.fileChannel, state.config.chunkSizeBytes);
                setupFileChannel();
            }
        };
        await state.pc.setRemoteDescription(new RTCSessionDescription(sdp));
        var answer = await state.pc.createAnswer();
        await state.pc.setLocalDescription(answer);
        App.sendWs(state.ws, { type: "answer", sdp: state.pc.localDescription });
    }

    function setupControlChannel() {
        state.controlChannel.onmessage = function (event) {
            var message = JSON.parse(event.data);
            if (message.type === "manifest") {
                handleManifest(message);
            } else if (message.type === "sender-finished" || message.type === "complete") {
                handleSenderFinished(message);
            } else if (message.type === "error") {
                failTransfer(message.code || App.ErrorCodes.REMOTE_ERROR, message.message || "송신자 오류");
            }
        };
    }

    function handleManifest(manifest) {
        state.manifest = manifest;
        if (manifest.file_size !== state.room.file_size) {
            failTransfer(App.ErrorCodes.METADATA_MISMATCH, "파일 크기 metadata가 일치하지 않습니다.");
            return;
        }
        state.bytesReceived = 0;
        state.chunkIndex = -1;
        state.senderFinished = false;
        state.finalizing = false;
        state.completed = false;
        state.failed = false;
        state.startedAt = performance.now();
        App.setText("transferStatusText", "전송 중");
        App.updateProgress(0, state.room.file_size, state.startedAt);
        App.sendWs(state.ws, { type: "transfer-started" });
        sendAck(true);
    }

    function setupFileChannel() {
        state.fileChannel.binaryType = "arraybuffer";
        state.fileChannel.onmessage = function (event) {
            state.writeQueue = state.writeQueue.then(function () {
                return writeChunk(event.data);
            }).then(function () {
                return maybeFinalizeReceive();
            }).catch(function (error) {
                failTransfer(App.ErrorCodes.STORAGE_WRITE_FAILED, error.message || "수신 파일 처리 실패");
            });
        };
        state.fileChannel.onclose = function () {
            if (!state.completed && !state.failed && state.bytesReceived < expectedSize()) {
                if (state.senderFinished) {
                    App.setText("transferStatusText", "남은 데이터 대기 중");
                    scheduleMissingDataTimeout();
                } else {
                    failTransfer(App.ErrorCodes.DATA_CHANNEL_CLOSED, sizeFailureMessage(expectedSize(), state.bytesReceived));
                }
            }
        };
    }

    async function writeChunk(data) {
        if (state.completed || state.failed) {
            return;
        }
        var arrayBuffer = data instanceof Blob ? await data.arrayBuffer() : data;
        var view = new Uint8Array(arrayBuffer);
        var expected = expectedSize();
        if (state.bytesReceived + view.byteLength > expected) {
            failTransfer(App.ErrorCodes.SIZE_MISMATCH, sizeFailureMessage(expected, state.bytesReceived + view.byteLength));
            return;
        }
        try {
            if (state.writer) {
                await state.writer.write(view);
            } else {
                state.fallbackChunks.push(arrayBuffer);
            }
        } catch (error) {
            failTransfer(App.ErrorCodes.STORAGE_WRITE_FAILED, error.message || "저장 파일 쓰기 실패");
            return;
        }
        state.bytesReceived += view.byteLength;
        state.chunkIndex += 1;
        App.updateProgress(state.bytesReceived, expected, state.startedAt);
        sendPeriodicAck();
        sendPeriodicServerProgress();
        if (state.senderFinished && state.bytesReceived < expected) {
            scheduleMissingDataTimeout();
        }
    }

    function expectedSize() {
        return state.manifest ? state.manifest.file_size : state.room.file_size;
    }

    function sendPeriodicAck() {
        var now = Date.now();
        if (
            now - state.lastAckAt >= 1000 ||
            state.chunkIndex % ACK_CHUNK_INTERVAL === 0 ||
            state.bytesReceived === expectedSize()
        ) {
            sendAck(false);
            state.lastAckAt = now;
        }
    }

    function sendPeriodicServerProgress() {
        var now = Date.now();
        if (now - state.lastServerProgressAt >= 1000) {
            state.lastServerProgressAt = now;
            App.sendWs(state.ws, {
                type: "transfer-progress",
                bytes_received: state.bytesReceived
            });
        }
    }

    function sendAck(initial) {
        if (!state.controlChannel || state.controlChannel.readyState !== "open") {
            return;
        }
        state.controlChannel.send(JSON.stringify({
            type: "ack",
            transfer_id: state.manifest ? state.manifest.transfer_id : null,
            received_bytes: initial ? 0 : state.bytesReceived,
            last_chunk_index: state.chunkIndex
        }));
    }

    function handleSenderFinished(message) {
        state.senderFinished = true;
        if (typeof message.total_bytes === "number" && message.total_bytes !== expectedSize()) {
            failTransfer(App.ErrorCodes.SIZE_MISMATCH, "송신자가 보고한 전체 크기가 원본 파일 크기와 일치하지 않습니다.");
            return;
        }
        if (state.bytesReceived < expectedSize()) {
            App.setText("transferStatusText", "남은 데이터 대기 중");
            scheduleMissingDataTimeout();
        }
        maybeFinalizeReceive().catch(function (error) {
            failTransfer(App.ErrorCodes.STORAGE_CLOSE_FAILED, error.message || "수신 파일 완료 처리 실패");
        });
    }

    async function maybeFinalizeReceive() {
        if (state.completed || state.finalizing || state.failed || !state.senderFinished || !state.manifest) {
            return;
        }
        if (state.bytesReceived !== expectedSize()) {
            return;
        }
        clearMissingDataTimeout();
        state.finalizing = true;
        if (state.writer) {
            try {
                await state.writer.close();
            } catch (error) {
                failTransfer(App.ErrorCodes.STORAGE_CLOSE_FAILED, error.message || "저장 파일 닫기 실패");
                return;
            }
        } else {
            var blob = new Blob(state.fallbackChunks, { type: state.room.mime_type || "application/octet-stream" });
            var link = App.byId("downloadLink");
            if (state.downloadUrl) {
                URL.revokeObjectURL(state.downloadUrl);
            }
            state.downloadUrl = URL.createObjectURL(blob);
            link.href = state.downloadUrl;
            link.download = state.room.file_name;
            App.show("downloadLink", true);
        }
        state.completed = true;
        state.finalizing = false;
        App.setText("transferStatusText", "완료");
        App.updateProgress(expectedSize(), expectedSize(), state.startedAt);
        state.controlChannel.send(JSON.stringify({
            type: "receiver-complete",
            transfer_id: state.manifest.transfer_id,
            total_bytes: state.bytesReceived,
            last_chunk_index: state.chunkIndex
        }));
        App.sendWs(state.ws, {
            type: "transfer-completed",
            total_bytes: state.bytesReceived,
            direct_p2p: App.byId("pathStatusText").textContent !== "TURN relay",
            turn_used: App.byId("pathStatusText").textContent === "TURN relay"
        });
        App.stopTransferKeepAlive();
    }

    function clearMissingDataTimeout() {
        if (state.missingDataTimer) {
            window.clearTimeout(state.missingDataTimer);
            state.missingDataTimer = null;
        }
    }

    function scheduleMissingDataTimeout() {
        clearMissingDataTimeout();
        state.missingDataTimer = window.setTimeout(function () {
            if (!state.completed && state.bytesReceived < expectedSize()) {
                failTransfer(App.ErrorCodes.TRANSFER_TIMEOUT, sizeFailureMessage(expectedSize(), state.bytesReceived));
            }
        }, (state.config.activeTransferIdleTimeoutSeconds || 180) * 1000);
    }

    function sizeFailureMessage(expected, received) {
        var missing = Math.max(0, expected - received);
        return "받은 파일 크기가 원본 파일 크기와 일치하지 않습니다. expected size: " +
            expected + ", received size: " + received + ", missing bytes: " + missing;
    }

    function failTransfer(code, message) {
        if (message === undefined) {
            message = code;
            code = App.ErrorCodes.UNKNOWN;
        }
        if (state.completed || state.failed) {
            return;
        }
        state.failed = true;
        clearMissingDataTimeout();
        if (state.controlChannel && state.controlChannel.readyState === "open") {
            state.controlChannel.send(JSON.stringify({
                type: "error",
                transfer_id: state.manifest ? state.manifest.transfer_id : null,
                code: code,
                message: message
            }));
        }
        App.setText("transferStatusText", "실패");
        setError(App.displayError(code, message));
        App.sendWs(state.ws, { type: "transfer-failed", error_code: code, reason: message });
        App.stopTransferKeepAlive();
    }

    document.addEventListener("DOMContentLoaded", function () {
        App.byId("lookupButton").addEventListener("click", lookupRoom);
        App.byId("connectButton").addEventListener("click", prepareAndConnect);
        var code = App.queryCode().replace(/\D/g, "").slice(0, 6);
        if (code.length === 6) {
            App.byId("codeInput").value = code;
            lookupRoom();
        }
    });
}());
