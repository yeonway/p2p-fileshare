(function () {
    "use strict";

    var ACK_CHUNK_INTERVAL = 16;

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
            openWebSocket();
        } catch (error) {
            App.byId("connectButton").disabled = false;
            setError(error.message);
        }
    }

    async function prepareWriter() {
        if ("showSaveFilePicker" in window) {
            var handle = await window.showSaveFilePicker({
                suggestedName: state.room.file_name
            });
            state.writer = await handle.createWritable();
            state.fallbackMode = false;
            App.show("fallbackWarning", false);
        } else {
            state.fallbackMode = true;
            state.fallbackChunks = [];
            App.show("fallbackWarning", true);
        }
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
                failTransfer(message.message || "송신자 오류");
            }
        };
    }

    function handleManifest(manifest) {
        state.manifest = manifest;
        if (manifest.file_size !== state.room.file_size) {
            failTransfer("파일 크기 metadata가 일치하지 않습니다.");
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
                failTransfer(error.message);
            });
        };
        state.fileChannel.onclose = function () {
            if (!state.completed && state.senderFinished && state.bytesReceived < expectedSize()) {
                App.setText("transferStatusText", "남은 데이터 대기 중");
                scheduleMissingDataTimeout();
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
            throw new Error(sizeFailureMessage(expected, state.bytesReceived + view.byteLength));
        }
        if (state.writer) {
            await state.writer.write(view);
        } else {
            state.fallbackChunks.push(arrayBuffer);
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
            failTransfer("송신자가 보고한 전체 크기가 원본 파일 크기와 일치하지 않습니다.");
            return;
        }
        if (state.bytesReceived < expectedSize()) {
            App.setText("transferStatusText", "남은 데이터 대기 중");
            scheduleMissingDataTimeout();
        }
        maybeFinalizeReceive().catch(function (error) {
            failTransfer(error.message);
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
            await state.writer.close();
        } else {
            var blob = new Blob(state.fallbackChunks, { type: state.room.mime_type || "application/octet-stream" });
            var link = App.byId("downloadLink");
            link.href = URL.createObjectURL(blob);
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
                failTransfer(sizeFailureMessage(expectedSize(), state.bytesReceived));
            }
        }, (state.config.activeTransferIdleTimeoutSeconds || 180) * 1000);
    }

    function sizeFailureMessage(expected, received) {
        var missing = Math.max(0, expected - received);
        return "받은 파일 크기가 원본 파일 크기와 일치하지 않습니다. expected size: " +
            expected + ", received size: " + received + ", missing bytes: " + missing;
    }

    function failTransfer(message) {
        if (state.completed || state.failed) {
            return;
        }
        state.failed = true;
        clearMissingDataTimeout();
        if (state.controlChannel && state.controlChannel.readyState === "open") {
            state.controlChannel.send(JSON.stringify({
                type: "error",
                transfer_id: state.manifest ? state.manifest.transfer_id : null,
                message: message
            }));
        }
        App.setText("transferStatusText", "실패");
        setError(message);
        App.sendWs(state.ws, { type: "transfer-failed", reason: message });
    }

    document.addEventListener("DOMContentLoaded", function () {
        App.byId("lookupButton").addEventListener("click", lookupRoom);
        App.byId("connectButton").addEventListener("click", prepareAndConnect);
    });
}());
